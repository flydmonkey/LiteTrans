import Foundation

public func sourceStem(_ displayName: String) -> String {
    let name = displayName.split(separator: "/").last.map(String.init) ?? displayName
    if let dot = name.lastIndex(of: "."), dot != name.startIndex {
        let stem = String(name[..<dot])
        return stem.isEmpty ? "output" : stem
    }
    return name.isEmpty ? "output" : name
}

public func partialOutputPath(_ output: String) -> String {
    let lastSlash = output.lastIndex(of: "/")
    let parent = lastSlash.map { String(output[..<$0]) } ?? ""
    let fileName = lastSlash.map { String(output[output.index(after: $0)...]) } ?? output
    let ext: String
    let stem: String
    if let dot = fileName.lastIndex(of: ".") {
        ext = String(fileName[fileName.index(after: dot)...])
        stem = String(fileName[..<dot])
    } else {
        ext = "bin"
        stem = fileName
    }
    let actualStem = stem.isEmpty ? "output" : stem
    let partialName = "\(actualStem).partial.\(ext)"
    return parent.isEmpty ? partialName : "\(parent)/\(partialName)"
}

public func allocateOutputPath(outputDir: String, stem: String, ext: String, exists: (String) -> Bool) -> String {
    let dir = outputDir.hasSuffix("/") ? String(outputDir.dropLast()) : outputDir
    let candidate = "\(dir)/\(stem).\(ext)"
    if !exists(candidate) { return candidate }
    var index = 1
    while true {
        let numbered = "\(dir)/\(stem)-\(index).\(ext)"
        if !exists(numbered) { return numbered }
        index += 1
    }
}

public func sanitizeRenameStem(_ raw: String) -> String? {
    let trimmed = raw.trimmingCharacters(in: .whitespacesAndNewlines)
    if trimmed.isEmpty || trimmed.contains("/") || trimmed.contains("\\") { return nil }
    var cleaned = trimmed
    for ch in [":", "*", "?", "\"", "<", ">", "|"] {
        cleaned = cleaned.replacingOccurrences(of: ch, with: "")
    }
    cleaned = cleaned.replacingOccurrences(of: #"\s+"#, with: " ", options: .regularExpression)
        .trimmingCharacters(in: .whitespacesAndNewlines)
        .trimmingCharacters(in: CharacterSet(charactersIn: "."))
    let stem = cleaned.split(separator: ".").dropLast().joined(separator: ".")
    let resolved = cleaned.contains(".") ? stem : cleaned
    if resolved.isEmpty || resolved == "." || resolved == ".." { return nil }
    return String(resolved.prefix(80))
}

public func canRenameJob(_ status: JobStatus) -> Bool { status == .completed }
