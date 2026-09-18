import Foundation
import ImageIO
import PDFKit
import Photos
import UniformTypeIdentifiers
import UIKit

struct DocumentRunError: Error, LocalizedError {
    let message: String
    var errorDescription: String? { message }
}

struct DocumentEngine: Sendable {
    func run(job: Job, onProgress: @escaping @Sendable (Double) -> Void) async throws {
        try checkCancelled()
        let source = try sourceURL(for: job)
        let destinations = try outputDestinations(for: job)
        let preset = job.config.preset
        if preset == "office-pdf" {
            onProgress(0)
            try convertOffice(source: source, destination: destinations[0])
            try checkCancelled()
            onProgress(100)
            return
        }

        if preset.hasPrefix("image-") {
            onProgress(0)
            try convertImage(job: job, source: source, destination: destinations[0])
            try checkCancelled()
            onProgress(100)
        } else {
            try await convertPDF(job: job, source: source, destinations: destinations, onProgress: onProgress)
        }

        if documentResultIsImage(preset), job.outputKind == .photos {
            for path in destinations {
                try checkCancelled()
                try await saveImageToPhotos(URL(fileURLWithPath: path))
            }
        }
    }

    private func convertImage(job: Job, source: URL, destination: String) throws {
        let image = try decodeImage(source)
        try checkCancelled()
        let destExt = URL(fileURLWithPath: destination).pathExtension.lowercased()
        let quality = job.config.quality ?? "standard"
        let encoded: CGImage
        let compression: Double?
        let ext: String
        if job.config.preset == "image-compress" {
            ext = destExt.isEmpty ? keptImageExtension(job.displayName) : destExt
            if ["png", "bmp"].contains(ext) {
                encoded = scaledImage(image, scale: scaleForQuality(quality))
                compression = nil
            } else {
                encoded = image
                compression = lossyQuality(for: ext, quality: quality, compressing: true)
            }
        } else {
            ext = destExt
            encoded = image
            compression = lossyQuality(for: destExt, quality: quality, compressing: false)
        }
        try writeReplacing(path: destination) { partial in
            try writeImage(encoded, to: partial, ext: ext, quality: compression)
        }
    }

    private func convertOffice(source: URL, destination: String) throws {
        let data: Data
        do {
            data = try Data(contentsOf: source)
        } catch {
            throw DocumentRunError(message: String(localized: "error_cannot_convert_document"))
        }
        let blocks: [OfficeBlock]
        do {
            blocks = try officeBlocksFromDocx(data)
        } catch {
            throw DocumentRunError(message: String(localized: "error_cannot_convert_document"))
        }
        try writeReplacing(path: destination) { partial in
            try writeOfficePdf(blocks, to: partial)
        }
    }

    private func convertPDF(
        job: Job,
        source: URL,
        destinations: [String],
        onProgress: @escaping @Sendable (Double) -> Void
    ) async throws {
        let document = try openPDF(source)
        let pageCount = max(document.pageCount, 1)
        let (lo, hi) = clampPageRange(
            start: job.media.pageStart ?? 1,
            end: job.media.pageEnd ?? pageCount,
            pageCount: pageCount
        )
        let quality = job.config.quality ?? "standard"
        switch job.config.preset {
        case "pdf-split":
            try splitPDF(document, range: lo...hi, destinations: destinations, onProgress: onProgress)
        case "pdf-image":
            try renderPDFImages(
                document,
                range: lo...hi,
                destinations: destinations,
                format: destinations.first.flatMap { URL(fileURLWithPath: $0).pathExtension.lowercased() } ?? "jpg",
                quality: quality,
                onProgress: onProgress
            )
        case "pdf-txt":
            try extractPDFText(document, range: lo...hi, destination: destinations[0], onProgress: onProgress)
        case "pdf-compress":
            try compressPDF(
                source: source,
                document: document,
                range: lo...hi,
                destination: destinations[0],
                quality: quality,
                onProgress: onProgress
            )
        default:
            throw DocumentRunError(message: LiteTransError.cannotTranscode.localizedDescription)
        }
    }

    private func splitPDF(
        _ document: PDFDocument,
        range: ClosedRange<Int>,
        destinations: [String],
        onProgress: @escaping @Sendable (Double) -> Void
    ) throws {
        let total = range.count
        for (offset, pageNumber) in range.enumerated() {
            try checkCancelled()
            guard offset < destinations.count, let page = document.page(at: pageNumber - 1) else {
                throw DocumentRunError(message: LiteTransError.cannotTranscode.localizedDescription)
            }
            let copy = (page.copy() as? PDFPage) ?? page
            let out = PDFDocument()
            out.insert(copy, at: 0)
            try writeReplacing(path: destinations[offset]) { partial in
                guard out.write(to: partial) else {
                    throw DocumentRunError(message: LiteTransError.cannotTranscode.localizedDescription)
                }
            }
            onProgress(Double(offset + 1) / Double(total) * 100)
        }
    }

    private func renderPDFImages(
        _ document: PDFDocument,
        range: ClosedRange<Int>,
        destinations: [String],
        format: String,
        quality: String,
        onProgress: @escaping @Sendable (Double) -> Void
    ) throws {
        let total = range.count
        let maxEdge = pdfImageMaxEdge(quality)
        let compression = lossyQuality(for: format, quality: quality, compressing: false)
        for (offset, pageNumber) in range.enumerated() {
            try checkCancelled()
            guard offset < destinations.count, let page = document.page(at: pageNumber - 1) else {
                throw DocumentRunError(message: LiteTransError.cannotTranscode.localizedDescription)
            }
            let image = try renderPage(page, maxEdge: maxEdge)
            try writeReplacing(path: destinations[offset]) { partial in
                try writeImage(image, to: partial, ext: format, quality: compression)
            }
            onProgress(Double(offset + 1) / Double(total) * 100)
        }
    }

    private func extractPDFText(
        _ document: PDFDocument,
        range: ClosedRange<Int>,
        destination: String,
        onProgress: @escaping @Sendable (Double) -> Void
    ) throws {
        let total = range.count
        var parts: [String] = []
        for (offset, pageNumber) in range.enumerated() {
            try checkCancelled()
            let text = document.page(at: pageNumber - 1)?.string ?? ""
            parts.append(text)
            onProgress(Double(offset + 1) / Double(total) * 100)
        }
        let combined = parts.joined(separator: "\n")
        if combined.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
            throw DocumentRunError(message: String(localized: "error_no_text"))
        }
        try writeReplacing(path: destination) { partial in
            try combined.write(to: partial, atomically: true, encoding: .utf8)
        }
    }

    private func compressPDF(
        source: URL,
        document: PDFDocument,
        range: ClosedRange<Int>,
        destination: String,
        quality: String,
        onProgress: @escaping @Sendable (Double) -> Void
    ) throws {
        guard let cgDocument = CGPDFDocument(source as CFURL) else {
            throw DocumentRunError(message: String(localized: "error_cannot_compress_pdf"))
        }
        if cgDocument.isEncrypted || !cgDocument.isUnlocked {
            throw DocumentRunError(message: String(localized: "error_encrypted_pdf"))
        }
        let maxEdge = pdfImageMaxEdge(quality)
        let out = PDFDocument()
        var compressedAny = false
        let total = range.count
        for (offset, pageNumber) in range.enumerated() {
            try checkCancelled()
            guard let pdfPage = document.page(at: pageNumber - 1),
                  let cgPage = cgDocument.page(at: pageNumber) else {
                throw DocumentRunError(message: String(localized: "error_cannot_compress_pdf"))
            }
            let images = extractPageImages(cgPage)
            let oversized = images.filter { max($0.width, $0.height) > maxEdge }
            if oversized.count == 1, images.count == 1, isImageOnlyPage(pdfPage) {
                let scaled = scaledToMaxEdge(images[0], maxEdge: maxEdge)
                guard let newPage = PDFPage(image: UIImage(cgImage: scaled)) else {
                    throw DocumentRunError(message: String(localized: "error_cannot_compress_pdf"))
                }
                out.insert(newPage, at: out.pageCount)
                compressedAny = true
            } else if oversized.isEmpty, let copy = pdfPage.copy() as? PDFPage {
                out.insert(copy, at: out.pageCount)
            } else {
                throw DocumentRunError(message: String(localized: "error_cannot_compress_pdf"))
            }
            onProgress(Double(offset + 1) / Double(total) * 100)
        }
        if !compressedAny {
            throw DocumentRunError(message: String(localized: "error_cannot_compress_pdf"))
        }
        try writeReplacing(path: destination) { partial in
            guard out.write(to: partial) else {
                throw DocumentRunError(message: String(localized: "error_cannot_compress_pdf"))
            }
        }
    }
}

private func openPDF(_ url: URL) throws -> PDFDocument {
    guard let document = PDFDocument(url: url) else {
        throw DocumentRunError(message: LiteTransError.cannotTranscode.localizedDescription)
    }
    if document.isEncrypted || document.isLocked {
        throw DocumentRunError(message: String(localized: "error_encrypted_pdf"))
    }
    return document
}

private func sourceURL(for job: Job) throws -> URL {
    if let url = URL(string: job.sourceUri), url.scheme != nil {
        return url
    }
    return URL(fileURLWithPath: job.sourceUri)
}

private func outputDestinations(for job: Job) throws -> [String] {
    if !job.outputPaths.isEmpty { return job.outputPaths }
    if let path = job.outputPath { return [path] }
    throw VideoExportError.missingOutput
}

private func checkCancelled() throws {
    if Task.isCancelled { throw CancellationError() }
}

private func decodeImage(_ url: URL) throws -> CGImage {
    guard let source = CGImageSourceCreateWithURL(url as CFURL, [kCGImageSourceShouldCache: false] as CFDictionary),
          CGImageSourceGetCount(source) > 0,
          let image = CGImageSourceCreateImageAtIndex(source, 0, nil) else {
        throw DocumentRunError(message: LiteTransError.cannotTranscode.localizedDescription)
    }
    return image
}

private func lossyQuality(for ext: String, quality: String, compressing: Bool) -> Double? {
    switch ext.lowercased() {
    case "jpg", "jpeg":
        if compressing {
            switch normalizedDocumentQuality(quality) {
            case "original": return 0.92
            case "small": return 0.60
            default: return 0.75
            }
        }
        return 0.75
    case "webp":
        return 0.80
    case "gif":
        return compressing ? 0.75 : nil
    default:
        return nil
    }
}

private func scaleForQuality(_ quality: String) -> CGFloat {
    normalizedDocumentQuality(quality) == "small" ? 0.7 : 1
}

private func normalizedDocumentQuality(_ quality: String) -> String {
    switch quality {
    case "original", "high": return "original"
    case "small": return "small"
    default: return "standard"
    }
}

private func pdfImageMaxEdge(_ quality: String) -> Int {
    switch normalizedDocumentQuality(quality) {
    case "original": return 1600
    case "small": return 800
    default: return 1200
    }
}

private func scaledImage(_ image: CGImage, scale: CGFloat) -> CGImage {
    if scale >= 1 { return image }
    return resizedImage(image, width: max(1, Int((CGFloat(image.width) * scale).rounded())), height: max(1, Int((CGFloat(image.height) * scale).rounded())))
}

private func scaledToMaxEdge(_ image: CGImage, maxEdge: Int) -> CGImage {
    let longest = max(image.width, image.height)
    guard longest > maxEdge else { return image }
    let scale = CGFloat(maxEdge) / CGFloat(longest)
    return scaledImage(image, scale: scale)
}

private func resizedImage(_ image: CGImage, width: Int, height: Int) -> CGImage {
    let colorSpace = image.colorSpace ?? CGColorSpaceCreateDeviceRGB()
    let bitmapInfo = CGBitmapInfo(rawValue: CGImageAlphaInfo.premultipliedLast.rawValue)
    guard let context = CGContext(
        data: nil,
        width: width,
        height: height,
        bitsPerComponent: 8,
        bytesPerRow: 0,
        space: colorSpace,
        bitmapInfo: bitmapInfo.rawValue
    ) else {
        return image
    }
    context.interpolationQuality = .high
    context.draw(image, in: CGRect(x: 0, y: 0, width: width, height: height))
    return context.makeImage() ?? image
}

private func renderPage(_ page: PDFPage, maxEdge: Int) throws -> CGImage {
    let bounds = page.bounds(for: .mediaBox)
    let longest = max(bounds.width, bounds.height)
    let scale = longest > 0 ? CGFloat(maxEdge) / longest : 1
    let size = CGSize(
        width: max(1, (bounds.width * scale).rounded()),
        height: max(1, (bounds.height * scale).rounded())
    )
    let thumb = page.thumbnail(of: size, for: .mediaBox)
    guard let image = thumb.cgImage else {
        throw DocumentRunError(message: LiteTransError.cannotTranscode.localizedDescription)
    }
    return image
}

private func writeImage(_ image: CGImage, to url: URL, ext: String, quality: Double?) throws {
    guard let uti = utiForExtension(ext),
          let destination = CGImageDestinationCreateWithURL(url as CFURL, uti, 1, nil) else {
        throw DocumentRunError(message: LiteTransError.cannotTranscode.localizedDescription)
    }
    var properties: [CFString: Any] = [:]
    if let quality {
        properties[kCGImageDestinationLossyCompressionQuality] = quality
    }
    CGImageDestinationAddImage(destination, image, properties as CFDictionary)
    guard CGImageDestinationFinalize(destination) else {
        throw DocumentRunError(message: LiteTransError.cannotTranscode.localizedDescription)
    }
}

private func utiForExtension(_ ext: String) -> CFString? {
    switch ext.lowercased() {
    case "jpg", "jpeg": return UTType.jpeg.identifier as CFString
    case "png": return UTType.png.identifier as CFString
    case "webp": return UTType.webP.identifier as CFString
    case "bmp": return UTType.bmp.identifier as CFString
    case "gif": return UTType.gif.identifier as CFString
    default: return nil
    }
}

private func writeReplacing(path: String, writePartial: (URL) throws -> Void) throws {
    let finalURL = URL(fileURLWithPath: path)
    try FileManager.default.createDirectory(at: finalURL.deletingLastPathComponent(), withIntermediateDirectories: true)
    let partialURL = URL(fileURLWithPath: partialOutputPath(path))
    try? FileManager.default.removeItem(at: partialURL)
    do {
        try writePartial(partialURL)
        if FileManager.default.fileExists(atPath: path) {
            try FileManager.default.removeItem(atPath: path)
        }
        try FileManager.default.moveItem(at: partialURL, to: finalURL)
    } catch {
        try? FileManager.default.removeItem(at: partialURL)
        throw error
    }
}

private func writeOfficePdf(_ blocks: [OfficeBlock], to url: URL) throws {
    let pageRect = CGRect(x: 0, y: 0, width: 595, height: 842)
    let renderer = UIGraphicsPDFRenderer(bounds: pageRect)
    let font = UIFont.systemFont(ofSize: 11)
    let lineHeight = max(font.lineHeight, 14)
    let margin: CGFloat = 48
    let maxWidth = pageRect.width - margin * 2
    let attributes: [NSAttributedString.Key: Any] = [
        .font: font,
        .foregroundColor: UIColor.black,
    ]
    try renderer.writePDF(to: url) { context in
        context.beginPage()
        var y = margin
        func ensureSpace() {
            if y + lineHeight > pageRect.height - margin {
                context.beginPage()
                y = margin
            }
        }
        func drawText(_ text: String) {
            for line in wrappedOfficeLines(text, font: font, width: maxWidth) {
                ensureSpace()
                (line as NSString).draw(at: CGPoint(x: margin, y: y), withAttributes: attributes)
                y += lineHeight
            }
        }
        for block in blocks {
            switch block {
            case .paragraph(let text):
                drawText(text)
            case .table(let rows):
                for row in rows {
                    drawText(row.joined(separator: "  "))
                }
            }
            y += lineHeight / 2
        }
    }
}

private func wrappedOfficeLines(_ text: String, font: UIFont, width: CGFloat) -> [String] {
    if text.isEmpty { return [""] }
    var lines: [String] = []
    var current = ""
    for character in text {
        if character == "\n" {
            lines.append(current)
            current = ""
            continue
        }
        let candidate = current + String(character)
        let candidateWidth = (candidate as NSString).size(withAttributes: [.font: font]).width
        if candidateWidth > width, !current.isEmpty {
            lines.append(current)
            current = String(character)
        } else {
            current = candidate
        }
    }
    lines.append(current)
    return lines
}

private func isImageOnlyPage(_ page: PDFPage) -> Bool {
    (page.string ?? "").trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
}

private func extractPageImages(_ page: CGPDFPage) -> [CGImage] {
    guard let dictionary = page.dictionary else { return [] }
    var resources: CGPDFDictionaryRef?
    guard CGPDFDictionaryGetDictionary(dictionary, "Resources", &resources), let resources else { return [] }
    var xObject: CGPDFDictionaryRef?
    guard CGPDFDictionaryGetDictionary(resources, "XObject", &xObject), let xObject else { return [] }
    var images: [CGImage] = []
    CGPDFDictionaryApplyBlock(xObject, { _, object, _ in
        var stream: CGPDFStreamRef?
        guard CGPDFObjectGetValue(object, .stream, &stream), let stream else { return true }
        guard let streamDict = CGPDFStreamGetDictionary(stream) else { return true }
        var subtype: UnsafePointer<Int8>?
        guard CGPDFDictionaryGetName(streamDict, "Subtype", &subtype),
              let subtype,
              String(cString: subtype) == "Image" else { return true }
        if let image = imageFromPDFStream(stream) {
            images.append(image)
        }
        return true
    }, nil)
    return images
}

private func imageFromPDFStream(_ stream: CGPDFStreamRef) -> CGImage? {
    var format = CGPDFDataFormat.raw
    guard let data = CGPDFStreamCopyData(stream, &format) as Data? else { return nil }
    guard let source = CGImageSourceCreateWithData(data as CFData, nil) else { return nil }
    return CGImageSourceCreateImageAtIndex(source, 0, nil)
}

private final class PhotosAddFlag: @unchecked Sendable {
    var value = false
}

func saveImageToPhotos(_ url: URL) async throws {
    let status = await PHPhotoLibrary.requestAuthorization(for: .addOnly)
    guard status == .authorized || status == .limited else {
        throw VideoExportError.photosDenied
    }
    let created = PhotosAddFlag()
    do {
        try await PHPhotoLibrary.shared().performChanges {
            if PHAssetChangeRequest.creationRequestForAssetFromImage(atFileURL: url) != nil {
                created.value = true
            } else if let data = try? Data(contentsOf: url), let image = UIImage(data: data) {
                PHAssetChangeRequest.creationRequestForAsset(from: image)
                created.value = true
            }
        }
    } catch {
        throw VideoExportError.photosSaveFailed
    }
    guard created.value else {
        throw VideoExportError.photosSaveFailed
    }
}
