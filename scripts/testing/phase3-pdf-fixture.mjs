// ASCII-only synthetic fixture shared by Node acceptance and k6; no personal information.
// With no argument this is the original one-line fixture. With an array of lines it renders them as separate lines
// (ASCII only; other characters become '?') so acceptance tests can use realistic resume content.
export function resumePdf(lines) {
  const pdfText = value => String(value).replace(/[^\x20-\x7e]/g, '?').replace(/([\\()])/g, '\\$1');
  const text = Array.isArray(lines)
    ? `BT /F1 10 Tf 50 760 Td 13 TL ${lines.map(line => `(${pdfText(line)}) Tj T*`).join(' ')} ET`
    : 'BT /F1 12 Tf 50 750 Td (Runtime Fixture. Skills: Java Spring Boot MongoDB. Experience: backend engineer.) Tj ET';
  const objects = ['<< /Type /Catalog /Pages 2 0 R >>', '<< /Type /Pages /Kids [3 0 R] /Count 1 >>', '<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Resources << /Font << /F1 4 0 R >> >> /Contents 5 0 R >>', '<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>', `<< /Length ${text.length} >>\nstream\n${text}\nendstream`];
  let pdf = '%PDF-1.4\n';
  const offsets = [];
  objects.forEach((value, index) => { offsets.push(pdf.length); pdf += `${index + 1} 0 obj\n${value}\nendobj\n`; });
  const xref = pdf.length;
  return pdf + `xref\n0 6\n0000000000 65535 f \n${offsets.map(value => `${String(value).padStart(10, '0')} 00000 n \n`).join('')}trailer\n<< /Size 6 /Root 1 0 R >>\nstartxref\n${xref}\n%%EOF\n`;
}

// Uncompressed ("stored") ZIP built in memory. Node only: uses Buffer, which k6 does not provide.
export function storedZip(entries) {
  let offset=0; const data=[],central=[];
  for(const [name,content] of Object.entries(entries)) {
    const filename=Buffer.from(name), bytes=Buffer.from(content); let crc=0xffffffff;
    for(const byte of bytes) { crc^=byte; for(let b=0;b<8;b++) crc=(crc>>>1)^((crc&1)?0xedb88320:0); } crc=(crc^0xffffffff)>>>0;
    const h=Buffer.alloc(30); h.writeUInt32LE(0x04034b50); h.writeUInt16LE(20,4); h.writeUInt32LE(crc,14); h.writeUInt32LE(bytes.length,18); h.writeUInt32LE(bytes.length,22); h.writeUInt16LE(filename.length,26);
    const c=Buffer.alloc(46); c.writeUInt32LE(0x02014b50); c.writeUInt16LE(20,4); c.writeUInt16LE(20,6); c.writeUInt32LE(crc,16); c.writeUInt32LE(bytes.length,20); c.writeUInt32LE(bytes.length,24); c.writeUInt16LE(filename.length,28); c.writeUInt32LE(offset,42);
    data.push(h,filename,bytes); central.push(c,filename); offset+=h.length+filename.length+bytes.length;
  }
  const directory=Buffer.concat(central), end=Buffer.alloc(22); end.writeUInt32LE(0x06054b50); end.writeUInt16LE(Object.keys(entries).length,8); end.writeUInt16LE(Object.keys(entries).length,10); end.writeUInt32LE(directory.length,12); end.writeUInt32LE(offset,16);
  return Buffer.concat([...data,directory,end]);
}
