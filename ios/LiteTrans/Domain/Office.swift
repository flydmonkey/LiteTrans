import Foundation
import zlib

public enum OfficeBlock: Equatable, Sendable {
    case paragraph(String)
    case table([[String]])
}

public func officeBlocksFromDocx(_ data: Data) throws -> [OfficeBlock] {
    guard let xml = try zipStoredFile(named: "word/document.xml", in: data) else {
        throw LiteTransError.cannotTranscode
    }
    let blocks = try parseWordDocument(xml)
    guard officeBlocksHaveContent(blocks) else {
        throw LiteTransError.cannotTranscode
    }
    return blocks
}

public func officeBlocksHaveContent(_ blocks: [OfficeBlock]) -> Bool {
    blocks.contains { block in
        switch block {
        case .paragraph(let text):
            !text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
        case .table(let rows):
            rows.contains { row in
                row.contains { !$0.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty }
            }
        }
    }
}

private let wordNamespace = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"

private func parseWordDocument(_ xml: Data) throws -> [OfficeBlock] {
    let parser = XMLParser(data: xml)
    let delegate = WordDocumentParser()
    parser.delegate = delegate
    parser.shouldProcessNamespaces = true
    guard parser.parse() else {
        throw LiteTransError.cannotTranscode
    }
    return delegate.blocks
}

private final class WordDocumentParser: NSObject, XMLParserDelegate {
    var blocks: [OfficeBlock] = []
    private var table: [[String]] = []
    private var row: [String] = []
    private var cell = ""
    private var paragraph = ""
    private var tableDepth = 0
    private var cellDepth = 0
    private var paragraphDepth = 0
    private var captureText = false

    func parser(
        _ parser: XMLParser,
        didStartElement elementName: String,
        namespaceURI: String?,
        qualifiedName: String?,
        attributes attributeDict: [String: String] = [:]
    ) {
        guard namespaceURI == wordNamespace || namespaceURI == nil else { return }
        switch elementName {
        case "tbl":
            if tableDepth == 0 {
                table = []
            }
            tableDepth += 1
        case "tr":
            if tableDepth == 1 { row = [] }
        case "tc":
            if tableDepth == 1 && cellDepth == 0 { cell = "" }
            cellDepth += 1
        case "p":
            if paragraphDepth == 0 { paragraph = "" }
            paragraphDepth += 1
        case "t":
            captureText = true
        case "tab":
            appendText("\t")
        case "br":
            appendText("\n")
        default:
            break
        }
    }

    func parser(_ parser: XMLParser, foundCharacters string: String) {
        guard captureText else { return }
        appendText(string)
    }

    func parser(
        _ parser: XMLParser,
        didEndElement elementName: String,
        namespaceURI: String?,
        qualifiedName: String?
    ) {
        guard namespaceURI == wordNamespace || namespaceURI == nil else { return }
        switch elementName {
        case "t":
            captureText = false
        case "p":
            paragraphDepth = max(0, paragraphDepth - 1)
            if paragraphDepth == 0, tableDepth == 0 {
                blocks.append(.paragraph(paragraph))
                paragraph = ""
            } else if paragraphDepth == 0, tableDepth == 1, cellDepth > 0, !paragraph.isEmpty {
                if !cell.isEmpty { cell += "\n" }
                cell += paragraph
                paragraph = ""
            }
        case "tc":
            cellDepth = max(0, cellDepth - 1)
            if cellDepth == 0, tableDepth == 1 {
                row.append(cell)
                cell = ""
            }
        case "tr":
            if tableDepth == 1 { table.append(row) }
        case "tbl":
            tableDepth = max(0, tableDepth - 1)
            if tableDepth == 0 {
                blocks.append(.table(table))
                table = []
            }
        default:
            break
        }
    }

    private func appendText(_ string: String) {
        if tableDepth == 1, cellDepth > 0 {
            if paragraphDepth > 0 {
                paragraph += string
            } else {
                cell += string
            }
        } else if tableDepth == 0 {
            paragraph += string
        }
    }
}

private func zipStoredFile(named name: String, in data: Data) throws -> Data? {
    guard data.count >= 22 else { throw LiteTransError.cannotTranscode }
    var eocd = data.count - 22
    var found = false
    while eocd >= 0 {
        if u32(data, eocd) == 0x0605_4b50 {
            found = true
            break
        }
        eocd -= 1
        if data.count - eocd > 65_535 + 22 { break }
    }
    guard found else { throw LiteTransError.cannotTranscode }
    let commentLength = Int(u16(data, eocd + 20))
    guard eocd + 22 + commentLength == data.count else { throw LiteTransError.cannotTranscode }
    let entries = Int(u16(data, eocd + 10))
    let cdSize = Int(u32(data, eocd + 12))
    let cdOffset = Int(u32(data, eocd + 16))
    guard cdOffset >= 0, cdSize >= 0, cdOffset + cdSize <= data.count else {
        throw LiteTransError.cannotTranscode
    }
    var cursor = cdOffset
    for _ in 0..<entries {
        guard cursor + 46 <= data.count, u32(data, cursor) == 0x0201_4b50 else {
            throw LiteTransError.cannotTranscode
        }
        let method = u16(data, cursor + 10)
        let compressedSize = Int(u32(data, cursor + 20))
        let uncompressedSize = Int(u32(data, cursor + 24))
        let nameLength = Int(u16(data, cursor + 28))
        let extraLength = Int(u16(data, cursor + 30))
        let commentLength = Int(u16(data, cursor + 32))
        let localOffset = Int(u32(data, cursor + 42))
        let nameStart = cursor + 46
        guard nameStart + nameLength <= data.count else { throw LiteTransError.cannotTranscode }
        let entryName = String(data: data.subdata(in: nameStart..<(nameStart + nameLength)), encoding: .utf8)
        if entryName == name {
            return try zipReadLocal(
                data,
                localOffset: localOffset,
                method: method,
                compressedSize: compressedSize,
                uncompressedSize: uncompressedSize
            )
        }
        cursor = nameStart + nameLength + extraLength + commentLength
    }
    return nil
}

private func zipReadLocal(
    _ data: Data,
    localOffset: Int,
    method: UInt16,
    compressedSize: Int,
    uncompressedSize: Int
) throws -> Data {
    guard localOffset + 30 <= data.count, u32(data, localOffset) == 0x0403_4b50 else {
        throw LiteTransError.cannotTranscode
    }
    let flags = u16(data, localOffset + 6)
    if flags & 1 != 0 { throw LiteTransError.cannotTranscode }
    let nameLength = Int(u16(data, localOffset + 26))
    let extraLength = Int(u16(data, localOffset + 28))
    let dataStart = localOffset + 30 + nameLength + extraLength
    guard dataStart + compressedSize <= data.count else { throw LiteTransError.cannotTranscode }
    let payload = data.subdata(in: dataStart..<(dataStart + compressedSize))
    switch method {
    case 0:
        return payload
    case 8:
        let inflated = try inflateRawDeflate(payload)
        if uncompressedSize > 0, inflated.count != uncompressedSize {
            throw LiteTransError.cannotTranscode
        }
        return inflated
    default:
        throw LiteTransError.cannotTranscode
    }
}

private func inflateRawDeflate(_ data: Data) throws -> Data {
    if data.isEmpty { return data }
    var stream = z_stream()
    let initStatus = inflateInit2_(&stream, -MAX_WBITS, ZLIB_VERSION, Int32(MemoryLayout<z_stream>.size))
    guard initStatus == Z_OK else { throw LiteTransError.cannotTranscode }
    defer { inflateEnd(&stream) }

    return try data.withUnsafeBytes { src -> Data in
        guard let base = src.bindMemory(to: Bytef.self).baseAddress else {
            throw LiteTransError.cannotTranscode
        }
        stream.next_in = UnsafeMutablePointer(mutating: base)
        stream.avail_in = uInt(src.count)
        var output = Data()
        var buffer = [Bytef](repeating: 0, count: 64 * 1024)
        while true {
            let (produced, status) = inflateChunk(&stream, buffer: &buffer)
            if produced > 0 {
                output.append(buffer, count: produced)
            }
            if status == Z_STREAM_END { return output }
            guard status == Z_OK else { throw LiteTransError.cannotTranscode }
        }
    }
}

private func inflateChunk(_ stream: inout z_stream, buffer: inout [Bytef]) -> (Int, Int32) {
    buffer.withUnsafeMutableBufferPointer { dst in
        stream.next_out = dst.baseAddress
        stream.avail_out = uInt(dst.count)
        let status = inflate(&stream, Z_NO_FLUSH)
        return (dst.count - Int(stream.avail_out), status)
    }
}

private func u16(_ data: Data, _ offset: Int) -> UInt16 {
    UInt16(data[offset]) | (UInt16(data[offset + 1]) << 8)
}

private func u32(_ data: Data, _ offset: Int) -> UInt32 {
    UInt32(data[offset])
        | (UInt32(data[offset + 1]) << 8)
        | (UInt32(data[offset + 2]) << 16)
        | (UInt32(data[offset + 3]) << 24)
}
