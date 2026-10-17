// Synthetic fixtures only. EICAR is the harmless standard antivirus test signature, not executable malware.
import assert from 'node:assert/strict';
import { randomUUID } from 'node:crypto';
import { readdirSync, readFileSync } from 'node:fs';
import { resumePdf, storedZip as zip } from './phase3-pdf-fixture.mjs';
const base=process.env.OPENHIRE_RESUME_PROCESSOR_URL || 'http://localhost:8086';
const headers={'X-Internal-Service-Secret':process.env.INTERNAL_SERVICE_SECRET || 'local-development-secret'};
const tenant=`phase3-resume-${randomUUID()}`;
const docx={
  '[Content_Types].xml':'<?xml version="1.0"?><Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/></Types>',
  '_rels/.rels':'<?xml version="1.0"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/></Relationships>',
  'word/document.xml':'<?xml version="1.0"?><w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:body><w:p><w:r><w:t>Fixture resume. Java Spring Boot MongoDB and backend engineering experience.</w:t></w:r></w:p></w:body></w:document>',
};
async function upload(name,bytes,expected=200) {
  const body=new FormData(); body.set('tenantId',tenant); body.set('applicationId',randomUUID()); body.set('file',new Blob([bytes]),name);
  const response=await fetch(`${base}/api/v1/internal/resumes`,{method:'POST',headers,body,signal:AbortSignal.timeout(15000)});
  assert.equal(response.status,expected,`Upload ${name}`); return expected===200?(await response.json()).id:null;
}
async function finish(id) {
  for(let i=0;i<100;i++) {
    const response=await fetch(`${base}/api/v1/internal/resumes/${id}?tenantId=${tenant}`,{headers}); assert.equal(response.status,200);
    const row=await response.json(); if(['DONE','FAILED','REJECTED'].includes(row.status)) return row;
    await new Promise(resolve=>setTimeout(resolve,1500));
  }
  throw Error('Resume did not finish');
}
await upload('fake.pdf','executable disguised as PDF',400);
await upload('traversal.docx',zip({...docx,'../escape.txt':'unsafe'}),400);
await upload('macros.docx',zip({...docx,'word/vbaProject.bin':'macro fixture'}),400);
assert.equal((await finish(await upload('clean.pdf',resumePdf()))).status,'DONE');
assert.equal((await finish(await upload('clean.docx',zip(docx)))).status,'DONE');
assert.equal((await finish(await upload('corrupt.pdf','%PDF-1.4 corrupt fixture'))).status,'FAILED');
const infected=await finish(await upload('antivirus-test.docx',zip({...docx,'eicar.txt':'X5O!P%@AP[4\\PZX54(P^)7CC)7}$EICAR-STANDARD-ANTIVIRUS-TEST-FILE!$H+H*'})));
assert.equal(infected.status,'REJECTED'); assert.equal(infected.error,'MALWARE_DETECTED');
console.log('PASS: real ClamAV clean/EICAR handling, PDF/DOCX extraction, corrupt content, traversal and macro rejection');
const root=new URL('../../../node_modules/.pnpm/',import.meta.url);
const installed=readdirSync(root).find(name=>name.startsWith('sharp@')); assert(installed,'Install workspace UI dependencies for the image-only PDF fixture');
const sharpDir=new URL(`${installed}/node_modules/sharp/`,root);
const sharpPkg=JSON.parse(readFileSync(new URL('package.json',sharpDir),'utf8'));
const {default:sharp}=await import(new URL(sharpPkg.module ?? sharpPkg.main,sharpDir));
const jpeg=await sharp(Buffer.from('<svg xmlns="http://www.w3.org/2000/svg" width="1200" height="350"><rect width="100%" height="100%" fill="white"/><text x="40" y="100" font-family="sans-serif" font-size="45">Resume fixture Java Spring Boot</text><text x="40" y="190" font-family="sans-serif" font-size="40">Backend engineering and MongoDB experience</text></svg>')).flatten({background:'white'}).jpeg().toBuffer();
const stream=Buffer.from('q 600 0 0 175 0 500 cm /Im0 Do Q');
const objects=[Buffer.from('<< /Type /Catalog /Pages 2 0 R >>'),Buffer.from('<< /Type /Pages /Kids [3 0 R] /Count 1 >>'),Buffer.from('<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Resources << /XObject << /Im0 4 0 R >> >> /Contents 5 0 R >>'),Buffer.concat([Buffer.from(`<< /Type /XObject /Subtype /Image /Width 1200 /Height 350 /ColorSpace /DeviceRGB /BitsPerComponent 8 /Filter /DCTDecode /Length ${jpeg.length} >>\nstream\n`),jpeg,Buffer.from('\nendstream')]),Buffer.concat([Buffer.from(`<< /Length ${stream.length} >>\nstream\n`),stream,Buffer.from('\nendstream')])];
let pdf=Buffer.from('%PDF-1.4\n'); const offsets=[];
objects.forEach((object,index)=>{offsets.push(pdf.length);pdf=Buffer.concat([pdf,Buffer.from(`${index+1} 0 obj\n`),object,Buffer.from('\nendobj\n')]);});
const xref=pdf.length;
pdf=Buffer.concat([pdf,Buffer.from(`xref\n0 6\n0000000000 65535 f \n${offsets.map(n=>`${String(n).padStart(10,'0')} 00000 n \n`).join('')}trailer\n<< /Size 6 /Root 1 0 R >>\nstartxref\n${xref}\n%%EOF\n`)]);
const ocr=await finish(await upload('image-only.pdf',pdf));
assert.equal(ocr.status,'DONE'); assert.equal(ocr.steps.ocr,'PASSED'); assert.match(ocr.text,/Java/i);
console.log('PASS: real Tika/Tesseract OCR extracts an image-only PDF. Fixture quarantine records retained under '+tenant);
