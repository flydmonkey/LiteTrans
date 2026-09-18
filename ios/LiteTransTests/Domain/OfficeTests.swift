import Foundation
import Testing
import zlib
@testable import LiteTransDomain

struct OfficeTests {
    @Test func docxBlocksIncludeParagraphAndTableInOrder() throws {
        let blocks = try officeBlocksFromDocx(sampleDocx())
        #expect(blocks.count >= 2)
        guard case .paragraph(let text) = blocks[0] else {
            Issue.record("first block should be a paragraph")
            return
        }
        #expect(text.contains("标题"))
        guard case .table(let rows) = blocks[1] else {
            Issue.record("second block should be a table")
            return
        }
        #expect(rows.first?.contains("左") == true)
        #expect(rows.first?.contains("右") == true)
    }

    @Test func emptyDocxFails() {
        #expect(throws: LiteTransError.cannotTranscode) {
            try officeBlocksFromDocx(sampleDocx(paragraph: "   ", includeTable: false))
        }
    }

    @Test func readsDeflatedDocx() throws {
        let blocks = try officeBlocksFromDocx(sampleDocx(deflate: true))
        guard case .paragraph(let text) = blocks[0] else {
            Issue.record("deflated docx should still parse")
            return
        }
        #expect(text.contains("标题"))
    }
}

private func sampleDocx(paragraph: String = "标题", includeTable: Bool = true, deflate: Bool = false) -> Data {
    var body = """
    <w:p><w:r><w:t>\(xmlEscape(paragraph))</w:t></w:r></w:p>
    """
    if includeTable {
        body += """
        <w:tbl><w:tr><w:tc><w:p><w:r><w:t>左</w:t></w:r></w:p></w:tc><w:tc><w:p><w:r><w:t>右</w:t></w:r></w:p></w:tc></w:tr></w:tbl>
        """
    }
    let document = """
    <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
    <w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
      <w:body>\(body)</w:body>
    </w:document>
    """
    return zipFiles(["word/document.xml": Data(document.utf8)], deflate: deflate)
}

private func xmlEscape(_ text: String) -> String {
    text
        .replacingOccurrences(of: "&", with: "&amp;")
        .replacingOccurrences(of: "<", with: "&lt;")
        .replacingOccurrences(of: ">", with: "&gt;")
}

private func zipFiles(_ files: [String: Data], deflate: Bool) -> Data {
    var locals = Data()
    var centrals = Data()
    for (name, content) in files.sorted(by: { $0.key < $1.key }) {
        let nameData = Data(name.utf8)
        let payload = deflate ? deflateRaw(content) : content
        let method: UInt16 = deflate ? 8 : 0
        let crc = crc32(content)
        let compressed = UInt32(payload.count)
        let size = UInt32(content.count)
        let offset = UInt32(locals.count)
        locals.append(contentsOf: zipUInt32(0x0403_4b50))
        locals.append(contentsOf: zipUInt16(20))
        locals.append(contentsOf: zipUInt16(0))
        locals.append(contentsOf: zipUInt16(method))
        locals.append(contentsOf: zipUInt16(0))
        locals.append(contentsOf: zipUInt16(0))
        locals.append(contentsOf: zipUInt32(crc))
        locals.append(contentsOf: zipUInt32(compressed))
        locals.append(contentsOf: zipUInt32(size))
        locals.append(contentsOf: zipUInt16(UInt16(nameData.count)))
        locals.append(contentsOf: zipUInt16(0))
        locals.append(nameData)
        locals.append(payload)

        centrals.append(contentsOf: zipUInt32(0x0201_4b50))
        centrals.append(contentsOf: zipUInt16(20))
        centrals.append(contentsOf: zipUInt16(20))
        centrals.append(contentsOf: zipUInt16(0))
        centrals.append(contentsOf: zipUInt16(method))
        centrals.append(contentsOf: zipUInt16(0))
        centrals.append(contentsOf: zipUInt16(0))
        centrals.append(contentsOf: zipUInt32(crc))
        centrals.append(contentsOf: zipUInt32(compressed))
        centrals.append(contentsOf: zipUInt32(size))
        centrals.append(contentsOf: zipUInt16(UInt16(nameData.count)))
        centrals.append(contentsOf: zipUInt16(0))
        centrals.append(contentsOf: zipUInt16(0))
        centrals.append(contentsOf: zipUInt16(0))
        centrals.append(contentsOf: zipUInt16(0))
        centrals.append(contentsOf: zipUInt32(0))
        centrals.append(contentsOf: zipUInt32(offset))
        centrals.append(nameData)
    }
    let cdOffset = UInt32(locals.count)
    let cdSize = UInt32(centrals.count)
    var out = locals + centrals
    out.append(contentsOf: zipUInt32(0x0605_4b50))
    out.append(contentsOf: zipUInt16(0))
    out.append(contentsOf: zipUInt16(0))
    out.append(contentsOf: zipUInt16(UInt16(files.count)))
    out.append(contentsOf: zipUInt16(UInt16(files.count)))
    out.append(contentsOf: zipUInt32(cdSize))
    out.append(contentsOf: zipUInt32(cdOffset))
    out.append(contentsOf: zipUInt16(0))
    return out
}

private func zipUInt16(_ value: UInt16) -> [UInt8] {
    [UInt8(value & 0xff), UInt8((value >> 8) & 0xff)]
}

private func zipUInt32(_ value: UInt32) -> [UInt8] {
    [
        UInt8(value & 0xff),
        UInt8((value >> 8) & 0xff),
        UInt8((value >> 16) & 0xff),
        UInt8((value >> 24) & 0xff),
    ]
}

private func crc32(_ data: Data) -> UInt32 {
    var crc: UInt32 = 0xffff_ffff
    for byte in data {
        crc ^= UInt32(byte)
        for _ in 0..<8 {
            crc = (crc & 1) != 0 ? (crc >> 1) ^ 0xedb8_8320 : crc >> 1
        }
    }
    return crc ^ 0xffff_ffff
}

private func deflateRaw(_ data: Data) -> Data {
    var stream = z_stream()
    deflateInit2_(&stream, Z_DEFAULT_COMPRESSION, Z_DEFLATED, -MAX_WBITS, 8, Z_DEFAULT_STRATEGY, ZLIB_VERSION, Int32(MemoryLayout<z_stream>.size))
    defer { deflateEnd(&stream) }
    return data.withUnsafeBytes { src in
        stream.next_in = UnsafeMutablePointer(mutating: src.bindMemory(to: Bytef.self).baseAddress!)
        stream.avail_in = uInt(src.count)
        var output = Data()
        var buffer = [Bytef](repeating: 0, count: 64 * 1024)
        while true {
            let (produced, status) = deflateChunk(&stream, buffer: &buffer)
            if produced > 0 {
                output.append(buffer, count: produced)
            }
            if status == Z_STREAM_END { break }
        }
        return output
    }
}

private func deflateChunk(_ stream: inout z_stream, buffer: inout [Bytef]) -> (Int, Int32) {
    buffer.withUnsafeMutableBufferPointer { dst in
        stream.next_out = dst.baseAddress
        stream.avail_out = uInt(dst.count)
        let status = deflate(&stream, Z_FINISH)
        return (dst.count - Int(stream.avail_out), status)
    }
}
