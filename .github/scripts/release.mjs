import fs from 'node:fs';
import { inventory } from './affected.mjs';

export function validateImages(images, expected, sha, registryPrefix) {
  const known = inventory().services;
  if (new Set(expected).size !== expected.length || expected.some(s => !known.includes(s))) throw Error('Invalid expected services');
  if (images.length !== expected.length) throw Error('Missing or excess images');
  const seen = new Set();
  for (const image of images) {
    if (!expected.includes(image.service) || seen.has(image.service)) throw Error('Unexpected/duplicate image');
    seen.add(image.service);
    if (image.sha !== sha || !/^[a-f0-9]{40}$/.test(sha)) throw Error('Source SHA mismatch');
    if (!/^sha256:[a-f0-9]{64}$/.test(image.digest)) throw Error('Invalid digest');
    if (!/^\d{12}\.dkr\.ecr\.ap-south-2\.amazonaws\.com\/openhire\/[a-z-]+$/.test(image.repository)) throw Error('Invalid ECR repository');
    if (!image.repository.endsWith('/' + image.service)) throw Error('Repository/service mismatch');
    if (registryPrefix && image.repository !== `${registryPrefix}/${image.service}`) throw Error('Unexpected ECR account');
  }
}

if (process.argv[1]?.endsWith('/release.mjs')) {
  const expected = JSON.parse(process.env.EXPECTED_SERVICES);
  const images = fs.existsSync('release-images') ? fs.readdirSync('release-images').map(dir => JSON.parse(fs.readFileSync(`release-images/${dir}/image.json`, 'utf8'))) : [];
  validateImages(images, expected, process.env.GITHUB_SHA);
  fs.writeFileSync('release.json', JSON.stringify({ sha: process.env.GITHUB_SHA, runId: process.env.GITHUB_RUN_ID, services: expected, images }, null, 2));
}
