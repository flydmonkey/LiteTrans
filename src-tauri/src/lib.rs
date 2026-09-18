mod args;
mod engine;
mod history;
mod job_store;
mod locale;
mod naming;
mod presets;
mod probe;
mod queue;
mod settings;

use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::{Arc, Mutex};

use tauri::{AppHandle, Emitter, Manager, State};
use tauri_plugin_dialog::DialogExt;

use args::validate;
use engine::{
    cancel_active, extract_preview_frame, prepare_preview, probe_media, transcode_job,
    ActiveTranscode,
};
use history::{mark_interrupted, parse_history_segment, remaining_jobs_after_clear_finished};
use job_store::{jobs_file, load_jobs, save_jobs};
use naming::{allocate_output_path, partial_output_path, sanitized_rename_stem, source_stem};
use presets::{list_presets, resolve_config, OutputConfig, PresetInfo};
use probe::{unreadable, MediaInfo};
use queue::{config_for_source, split_importable, EnqueueReport, Job, JobStatus, SkippedSource};
use settings::{load_from_path, save_to_path, settings_file, usable_output_dir, SessionSettings};

static NEXT_JOB_ID: AtomicU64 = AtomicU64::new(1);

pub struct AppState {
    pub jobs: Mutex<Vec<Job>>,
    pub output_dir: Mutex<Option<String>>,
    pub active: Arc<Mutex<Option<ActiveTranscode>>>,
    pub pumping: Mutex<bool>,
    pub config_dir: Mutex<PathBuf>,
}

impl Default for AppState {
    fn default() -> Self {
        Self {
            jobs: Mutex::new(Vec::new()),
            output_dir: Mutex::new(None),
            active: Arc::new(Mutex::new(None)),
            pumping: Mutex::new(false),
            config_dir: Mutex::new(PathBuf::new()),
        }
    }
}

fn lock_err<T>(result: Result<T, std::sync::PoisonError<T>>) -> Result<T, String> {
    result.map_err(|_| "内部状态锁失败".into())
}

fn session_settings_path(app: &AppHandle) -> Result<PathBuf, String> {
    let dir = app
        .path()
        .app_config_dir()
        .map_err(|err| format!("无法定位配置文件夹：{err}"))?;
    Ok(settings_file(&dir))
}

fn persist_output_dir(app: &AppHandle, path: &str) -> Result<(), String> {
    let file = session_settings_path(app)?;
    let mut settings = load_from_path(&file);
    settings.output_dir = Some(path.to_string());
    save_to_path(&file, &settings)
}

#[tauri::command]
fn list_output_presets() -> Vec<PresetInfo> {
    list_presets()
}

#[tauri::command]
async fn probe_media_command(path: String) -> MediaInfo {
    // Must be async so 4K ffprobe does not freeze the macOS UI thread.
    let probe_path = path.clone();
    tauri::async_runtime::spawn_blocking(move || probe_media(&probe_path))
        .await
        .unwrap_or_else(|_| unreadable(&path, "探测任务失败"))
}

#[tauri::command]
async fn pick_files(app: AppHandle) -> Result<Vec<String>, String> {
    // Must be async: sync commands run on the macOS main thread, and
    // blocking_pick_files() also needs that thread, which deadlocks the dialog.
    let files = app
        .dialog()
        .file()
        .set_title("选择视频")
        .add_filter(
            "视频",
            &[
                "mp4", "mkv", "mov", "avi", "webm", "flv", "wmv", "ts", "m4v", "3gp", "mpeg",
                "mpg", "m2ts", "vob", "mts",
            ],
        )
        .blocking_pick_files();

    Ok(files
        .unwrap_or_default()
        .into_iter()
        .filter_map(|file| file.into_path().ok())
        .map(|path| path.to_string_lossy().to_string())
        .collect())
}

#[tauri::command]
async fn pick_output_dir(app: AppHandle, state: State<'_, AppState>) -> Result<Option<String>, String> {
    let Some(folder) = app
        .dialog()
        .file()
        .set_title("选择输出目录")
        .blocking_pick_folder()
    else {
        return Ok(None);
    };
    let path = folder
        .into_path()
        .map_err(|err| format!("无法使用所选目录：{err}"))?
        .to_string_lossy()
        .to_string();
    *lock_err(state.output_dir.lock())? = Some(path.clone());
    persist_output_dir(&app, &path)?;
    Ok(Some(path))
}

#[tauri::command]
fn set_output_dir(app: AppHandle, state: State<AppState>, path: String) -> Result<(), String> {
    *lock_err(state.output_dir.lock())? = Some(path.clone());
    persist_output_dir(&app, &path)
}

#[tauri::command]
fn get_output_dir(state: State<AppState>) -> Result<Option<String>, String> {
    Ok(lock_err(state.output_dir.lock())?.clone())
}

#[tauri::command]
fn get_or_init_output_dir(app: AppHandle, state: State<AppState>) -> Result<String, String> {
    if let Some(existing) = lock_err(state.output_dir.lock())?.clone() {
        return Ok(existing);
    }
    if let Some(saved) = usable_output_dir(&load_from_path(&session_settings_path(&app)?)) {
        *lock_err(state.output_dir.lock())? = Some(saved.clone());
        return Ok(saved);
    }
    let mut dir = app
        .path()
        .download_dir()
        .map_err(|err| format!("无法定位下载文件夹：{err}"))?;
    dir.push("LiteTrans");
    std::fs::create_dir_all(&dir).map_err(|err| format!("无法创建保存文件夹：{err}"))?;
    let path = dir.to_string_lossy().to_string();
    *lock_err(state.output_dir.lock())? = Some(path.clone());
    persist_output_dir(&app, &path)?;
    Ok(path)
}

#[tauri::command]
fn load_session_settings(app: AppHandle) -> Result<SessionSettings, String> {
    Ok(load_from_path(&session_settings_path(&app)?))
}

#[tauri::command]
fn save_session_settings(app: AppHandle, settings: SessionSettings) -> Result<(), String> {
    let file = session_settings_path(&app)?;
    let mut current = load_from_path(&file);
    if settings.output_dir.is_some() {
        current.output_dir = settings.output_dir;
    }
    if settings.language.is_some() {
        current.language = settings.language;
    }
    current.preset = settings.preset;
    current.quality = settings.quality;
    current.max_width = settings.max_width;
    current.max_height = settings.max_height;
    save_to_path(&file, &current)
}

#[tauri::command]
fn list_jobs(state: State<AppState>) -> Result<Vec<Job>, String> {
    Ok(lock_err(state.jobs.lock())?.clone())
}

#[tauri::command]
async fn extract_preview_frame_command(path: String, time_secs: f64) -> Result<String, String> {
    tauri::async_runtime::spawn_blocking(move || extract_preview_frame(&path, time_secs))
        .await
        .map_err(|_| "抽帧任务失败".to_string())?
}

#[tauri::command]
async fn prepare_preview_command(
    app: AppHandle,
    path: String,
    container: Option<String>,
    video_codec: Option<String>,
    audio_codec: Option<String>,
    force: Option<bool>,
) -> Result<String, String> {
    let cache = app
        .path()
        .app_cache_dir()
        .map_err(|err| format!("无法定位预览缓存：{err}"))?
        .join("preview");
    tauri::async_runtime::spawn_blocking(move || {
        prepare_preview(
            &path,
            &cache,
            container.as_deref(),
            video_codec.as_deref(),
            audio_codec.as_deref(),
            force.unwrap_or(false),
        )
        .map(|p| p.to_string_lossy().to_string())
    })
    .await
    .map_err(|_| "预览任务失败".to_string())?
}

#[tauri::command]
fn clear_finished_jobs(
    app: AppHandle,
    state: State<AppState>,
    segment: String,
) -> Result<(), String> {
    let segment = parse_history_segment(&segment);
    {
        let mut jobs = lock_err(state.jobs.lock())?;
        *jobs = remaining_jobs_after_clear_finished(&jobs, segment);
    }
    emit_jobs(&app, &state)
}

#[tauri::command]
fn enqueue_jobs(
    app: AppHandle,
    state: State<AppState>,
    sources: Vec<MediaInfo>,
    config: OutputConfig,
    output_dir: String,
) -> Result<EnqueueReport, String> {
    if output_dir.trim().is_empty() {
        return Err("请先选择输出目录".into());
    }
    *lock_err(state.output_dir.lock())? = Some(output_dir.clone());
    persist_output_dir(&app, &output_dir)?;

    let resolved = resolve_config(&config)?;
    let (accepted, mut skipped) = split_importable(sources);

    let mut created = Vec::new();
    for media in accepted {
        if let Err(reason) = validate(&resolved, &media) {
            skipped.push(SkippedSource {
                path: media.path,
                reason,
            });
            continue;
        }
        let job_config = config_for_source(&config, &media);
        let stem = source_stem(&media.path);
        let output_path = allocate_output_path(
            Path::new(&output_dir),
            &stem,
            &resolved.extension,
            path_or_partial_exists,
        );
        let output_path_str = output_path.to_string_lossy().to_string();
        let display_name = output_path
            .file_name()
            .and_then(|s| s.to_str())
            .unwrap_or("output")
            .to_string();
        created.push(Job {
            id: format!("job-{}", NEXT_JOB_ID.fetch_add(1, Ordering::Relaxed)),
            source_path: media.path.clone(),
            output_path: Some(output_path_str.clone()),
            status: JobStatus::Queued,
            progress: 0.0,
            error: None,
            config: job_config,
            media,
            display_name,
            output_paths: vec![output_path_str],
            created_at_epoch_ms: Some(now_epoch_ms()),
            concat_source_paths: vec![],
        });
    }

    {
        let mut jobs = lock_err(state.jobs.lock())?;
        jobs.extend(created.clone());
    }

    emit_jobs(&app, &state)?;
    start_pump(app);
    Ok(EnqueueReport {
        jobs: created,
        skipped,
    })
}

#[tauri::command]
fn cancel_job(app: AppHandle, state: State<AppState>, id: String) -> Result<(), String> {
    let killed = cancel_active(&state.active, &id)?;
    {
        let mut jobs = lock_err(state.jobs.lock())?;
        if let Some(job) = jobs.iter_mut().find(|job| job.id == id) {
            match job.status {
                JobStatus::Running if killed => {
                    job.status = JobStatus::Cancelled;
                    job.error = Some("已取消".into());
                }
                JobStatus::Queued => {
                    job.status = JobStatus::Cancelled;
                    job.error = Some("已取消".into());
                }
                _ => {}
            }
        }
    }
    emit_jobs(&app, &state)
}

#[tauri::command]
fn retry_job(app: AppHandle, state: State<AppState>, id: String) -> Result<(), String> {
    let remembered = lock_err(state.output_dir.lock())?.clone().map(PathBuf::from);
    {
        let mut jobs = lock_err(state.jobs.lock())?;
        let Some(job) = jobs.iter_mut().find(|job| job.id == id) else {
            return Err("找不到该任务".into());
        };
        if job.status != JobStatus::Failed && job.status != JobStatus::Cancelled {
            return Err("只能重试失败的任务".into());
        }
        let resolved = resolve_config(&job.config)?;
        let stem = source_stem(&job.source_path);
        let output_dir = job
            .output_path
            .as_ref()
            .and_then(|p| Path::new(p).parent())
            .map(Path::to_path_buf)
            .or(remembered)
            .ok_or_else(|| "没有输出目录".to_string())?;
        let output_path = allocate_output_path(
            &output_dir,
            &stem,
            &resolved.extension,
            path_or_partial_exists,
        );
        let output_path_str = output_path.to_string_lossy().to_string();
        job.status = JobStatus::Queued;
        job.progress = 0.0;
        job.error = None;
        job.output_path = Some(output_path_str.clone());
        job.output_paths = vec![output_path_str];
        job.display_name = output_path
            .file_name()
            .and_then(|s| s.to_str())
            .unwrap_or("output")
            .to_string();
    }
    emit_jobs(&app, &state)?;
    start_pump(app);
    Ok(())
}

#[tauri::command]
fn delete_job(app: AppHandle, state: State<AppState>, id: String) -> Result<(), String> {
    let paths = {
        let mut jobs = lock_err(state.jobs.lock())?;
        let Some(index) = jobs.iter().position(|job| job.id == id) else {
            return Err("找不到该任务".into());
        };
        if matches!(jobs[index].status, JobStatus::Queued | JobStatus::Running) {
            return Err("进行中的任务请先取消".into());
        }
        let job = jobs.remove(index);
        if job.output_paths.is_empty() {
            job.output_path.into_iter().collect::<Vec<_>>()
        } else {
            job.output_paths
        }
    };
    for path in paths {
        let file = Path::new(&path);
        if file.exists() {
            let _ = std::fs::remove_file(file);
        }
    }
    emit_jobs(&app, &state)
}

#[tauri::command]
fn rename_job(
    app: AppHandle,
    state: State<AppState>,
    id: String,
    raw_name: String,
) -> Result<(), String> {
    let stem = sanitized_rename_stem(&raw_name).ok_or_else(|| "文件名不能为空".to_string())?;
    {
        let mut jobs = lock_err(state.jobs.lock())?;
        let Some(job) = jobs.iter_mut().find(|job| job.id == id) else {
            return Err("找不到该任务".into());
        };
        if job.status != JobStatus::Completed {
            return Err("只能重命名已完成的任务".into());
        }
        let current = job
            .output_paths
            .first()
            .cloned()
            .or_else(|| job.output_path.clone())
            .ok_or_else(|| "没有输出路径".to_string())?;
        let current_path = PathBuf::from(&current);
        let parent = current_path.parent().unwrap_or_else(|| Path::new("."));
        let ext = current_path
            .extension()
            .and_then(|s| s.to_str())
            .unwrap_or("");
        let dest = allocate_output_path(parent, &stem, ext, |path| {
            path.exists() && path != current_path.as_path()
        });
        if dest != current_path {
            std::fs::rename(&current_path, &dest).map_err(|err| format!("无法重命名：{err}"))?;
        }
        let dest_str = dest.to_string_lossy().to_string();
        let display_name = dest
            .file_name()
            .and_then(|s| s.to_str())
            .unwrap_or(&dest_str)
            .to_string();
        job.output_path = Some(dest_str.clone());
        if job.output_paths.is_empty() {
            job.output_paths.push(dest_str);
        } else {
            job.output_paths[0] = dest_str;
        }
        job.display_name = display_name;
    }
    emit_jobs(&app, &state)
}

#[tauri::command]
fn app_version() -> String {
    env!("CARGO_PKG_VERSION").to_string()
}

#[tauri::command]
fn remove_source_jobs(state: State<AppState>, path: String) -> Result<(), String> {
    let jobs = lock_err(state.jobs.lock())?;
    if jobs.iter().any(|job| {
        job.source_path == path && matches!(job.status, JobStatus::Running)
    }) {
        return Err("正在转码的文件不能移除".into());
    }
    Ok(())
}

fn path_or_partial_exists(path: &Path) -> bool {
    path.exists() || partial_output_path(path).exists()
}

fn now_epoch_ms() -> i64 {
    std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map(|d| d.as_millis() as i64)
        .unwrap_or(0)
}

fn persist_jobs_if_possible(app: &AppHandle, jobs: &[Job]) {
    if let Ok(dir) = app.path().app_config_dir() {
        let _ = save_jobs(&jobs_file(&dir), jobs);
    }
}

fn next_job_id_from(jobs: &[Job]) -> u64 {
    jobs.iter()
        .filter_map(|job| job.id.strip_prefix("job-")?.parse::<u64>().ok())
        .max()
        .unwrap_or(0)
        .saturating_add(1)
}

fn load_jobs_into_state(app: &AppHandle) {
    let Ok(dir) = app.path().app_config_dir() else {
        return;
    };
    let file = jobs_file(&dir);
    let mut jobs = load_jobs(&file);
    mark_interrupted(&mut jobs);
    let _ = save_jobs(&file, &jobs);
    NEXT_JOB_ID.store(next_job_id_from(&jobs), Ordering::Relaxed);
    let state = app.state::<AppState>();
    if let Ok(mut guard) = state.jobs.lock() {
        *guard = jobs;
    }
    if let Ok(mut config_dir) = state.config_dir.lock() {
        *config_dir = dir;
    };
}

fn emit_jobs(app: &AppHandle, state: &AppState) -> Result<(), String> {
    let jobs = lock_err(state.jobs.lock())?.clone();
    persist_jobs_if_possible(app, &jobs);
    app.emit("jobs-changed", jobs)
        .map_err(|err| format!("无法更新任务列表：{err}"))
}

fn start_pump(app: AppHandle) {
    let state = app.state::<AppState>();
    {
        let Ok(mut pumping) = state.pumping.lock() else {
            return;
        };
        if *pumping {
            return;
        }
        *pumping = true;
    }
    tauri::async_runtime::spawn(async move {
        pump_queue(app).await;
    });
}

async fn pump_queue(app: AppHandle) {
    loop {
        let next = {
            let state = app.state::<AppState>();
            let mut jobs = match state.jobs.lock() {
                Ok(jobs) => jobs,
                Err(_) => break,
            };
            match jobs.iter_mut().find(|job| job.status == JobStatus::Queued) {
                Some(job) => {
                    job.status = JobStatus::Running;
                    job.progress = 0.0;
                    Some(job.clone())
                }
                None => None,
            }
        };

        let Some(job) = next else {
            break;
        };

        let _ = app.emit("jobs-changed", current_jobs(&app));

        let output_path = match &job.output_path {
            Some(path) => PathBuf::from(path),
            None => {
                mark_job(&app, &job.id, JobStatus::Failed, None, Some("没有输出路径".into()));
                continue;
            }
        };

        let resolved = match resolve_config(&job.config) {
            Ok(config) => config,
            Err(err) => {
                mark_job(&app, &job.id, JobStatus::Failed, None, Some(err));
                continue;
            }
        };

        if let Err(reason) = validate(&resolved, &job.media) {
            mark_job(&app, &job.id, JobStatus::Failed, None, Some(reason));
            continue;
        }

        let active = app.state::<AppState>().active.clone();
        let job_id = job.id.clone();
        let media = job.media.clone();
        let result = tauri::async_runtime::spawn_blocking({
            let app = app.clone();
            let output_path = output_path.clone();
            move || transcode_job(&app, &job_id, &media, &resolved, &output_path, &active)
        })
        .await;

        match result {
            Ok(Ok(())) => {
                mark_job(
                    &app,
                    &job.id,
                    JobStatus::Completed,
                    Some(100.0),
                    None,
                );
            }
            Ok(Err(err)) if err == "cancelled" => {
                mark_job(
                    &app,
                    &job.id,
                    JobStatus::Cancelled,
                    None,
                    Some("已取消".into()),
                );
            }
            Ok(Err(err)) => {
                mark_job(&app, &job.id, JobStatus::Failed, None, Some(err));
            }
            Err(err) => {
                mark_job(
                    &app,
                    &job.id,
                    JobStatus::Failed,
                    None,
                    Some(format!("转码线程失败：{err}")),
                );
            }
        }
    }

    if let Ok(mut pumping) = app.state::<AppState>().pumping.lock() {
        *pumping = false;
    }
}

fn current_jobs(app: &AppHandle) -> Vec<Job> {
    app.state::<AppState>()
        .jobs
        .lock()
        .map(|jobs| jobs.clone())
        .unwrap_or_default()
}

fn mark_job(
    app: &AppHandle,
    id: &str,
    status: JobStatus,
    progress: Option<f64>,
    error: Option<String>,
) {
    if let Ok(mut jobs) = app.state::<AppState>().jobs.lock() {
        if let Some(job) = jobs.iter_mut().find(|job| job.id == id) {
            job.status = status;
            if let Some(progress) = progress {
                job.progress = progress;
            }
            job.error = error;
        }
    }
    let jobs = current_jobs(app);
    persist_jobs_if_possible(app, &jobs);
    let _ = app.emit("jobs-changed", jobs);
}

#[cfg_attr(mobile, tauri::mobile_entry_point)]
pub fn run() {
    tauri::Builder::default()
        .plugin(tauri_plugin_opener::init())
        .plugin(tauri_plugin_dialog::init())
        .manage(AppState::default())
        .setup(|app| {
            load_jobs_into_state(app.handle());
            Ok(())
        })
        .invoke_handler(tauri::generate_handler![
            list_output_presets,
            probe_media_command,
            pick_files,
            pick_output_dir,
            set_output_dir,
            get_output_dir,
            get_or_init_output_dir,
            load_session_settings,
            save_session_settings,
            list_jobs,
            extract_preview_frame_command,
            prepare_preview_command,
            enqueue_jobs,
            cancel_job,
            retry_job,
            clear_finished_jobs,
            delete_job,
            rename_job,
            app_version,
            remove_source_jobs,
        ])
        .run(tauri::generate_context!())
        .expect("error while running tauri application");
}
