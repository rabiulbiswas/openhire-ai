import assert from 'node:assert/strict';

/** Continue only the tagged synthetic applications using normal, role-checked workflow APIs. */
export async function enrichDemo({ api, people, asRecruiter, asManager, asHr, asInterviewer, manifest, checkpoint, tenantId, slug }) {
  if (manifest.completed.includes('workflow-enrichment')) return;
  const progress = message => console.log(`PASS: ${message}`);
  const sleep = ms => new Promise(resolve => setTimeout(resolve, ms));
  const records = async () => (await api('/applications?size=100')).data;
  async function app(id) { return (await records()).find(row => row.applicationId === id); }
  // Use the real extraction and AI workers; never insert invented model assessments or fabricated audit entries.
  const deadline = Date.now() + 240000;
  while (Date.now() < deadline) {
    const rows = await records();
    const ready = manifest.applications.filter(a => rows.some(r => r.applicationId === a.id && ['AI_ASSESSED', 'SHORTLISTED', 'INTERVIEWING', 'OFFER', 'HIRED', 'REJECTED'].includes(r.stage)));
    console.log(`Screening ${ready.length}/${manifest.applications.length} synthetic applications ready`);
    if (ready.length === manifest.applications.length) break;
    await sleep(10000);
  }
  const target = manifest.applications;
  assert(target.length >= 8, 'Core candidate intake must finish before enrichment');
  // A delayed worker must remain resumable, not be recorded as a completed seed.
  let pendingScreening = false;
  for (const index of [5, 4, 2, 3, 0, 1]) {
    const candidate = target[index];
    let current = await app(candidate.id);
    if (!current || current.stage === 'NEEDS_ATTENTION' || !['AI_ASSESSED', 'SHORTLISTED', 'INTERVIEWING', 'OFFER', 'HIRED'].includes(current.stage)) {
      pendingScreening = true;
      manifest.warnings ??= [];
      manifest.warnings.push(`${candidate.name}: screening not ready (${current?.stage || 'missing'}); no stage or policy bypass`);
      checkpoint(); continue;
    }
    if (current.stage === 'AI_ASSESSED') await api(`/applications/${candidate.id}/shortlist`, undefined, 'POST');
    if (index === 5) { progress('shortlisted demo candidate'); continue; }
    current = await app(candidate.id);
    if (current.stage === 'SHORTLISTED') await api(`/applications/${candidate.id}/stage`, { stage: 'INTERVIEWING' }, 'PATCH');
    if ([4, 2].includes(index)) {
      const loop = await api(`/applications/${candidate.id}/interview-loop`, undefined, 'POST');
      const round = loop.rounds[0];
      if (!round.start) await api(`/rounds/${round.id}/schedule`, { start: new Date(Date.now() + (index === 4 ? 2 : -1) * 86400000).toISOString(), timezone: 'Asia/Kolkata', panel: [{ name: `${slug} Demo interviewer`, email: people.INTERVIEWER.username, role: 'INTERVIEWER' }], meetingUrl: 'https://example.test/demo-interview' }, 'POST');
      if (!manifest.interviews.includes(round.id)) manifest.interviews.push(round.id);
      checkpoint();
      if (index === 2 && !round.scorecardsSubmitted) await api(`/rounds/${round.id}/scorecards`, { ratings: [{ competencyId: 'backend', rating: 'GOOD' }, { competencyId: 'communication', rating: 'GOOD' }], evidence: 'Synthetic local demo interview: API design and communication examples. Not a real assessment.' }, 'POST', asInterviewer);
      progress(index === 4 ? 'future interview assigned to the demo interviewer' : 'demo scorecard submitted through the interviewer API');
    }
    if (index === 4) continue;
    current = await app(candidate.id);
    if (current.stage === 'INTERVIEWING') await api(`/applications/${candidate.id}/decision`, { decision: 'HIRE', reason: 'Synthetic local seed workflow only; not a real hiring decision.' }, 'POST');
    let offerRecord = manifest.offers.find(o => o.applicationId === candidate.id);
    let offer = offerRecord ? await api(`/offers/${offerRecord.id}`) : await api(`/applications/${candidate.id}/offer`, {}, 'POST');
    if (!offerRecord) { offerRecord = { id: offer.id, applicationId: candidate.id, target: index === 2 ? 'DRAFT' : index === 3 ? 'PENDING_APPROVAL' : index === 1 ? 'ACCEPTED' : 'SENT' }; manifest.offers.push(offerRecord); checkpoint(); }
    if (offer.status === 'DRAFT') {
      const day = n => new Date(Date.now() + n * 86400000).toISOString().slice(0, 10);
      offer = await api(`/offers/${offer.id}`, { terms: { base: 22, joiningDate: day(30), expiryDate: day(7), reportsTo: 'Demo hiring manager', location: 'Remote - synthetic demo' }, coverNote: 'Synthetic local testing only. Not a real employment offer.' }, 'PATCH');
      if (index !== 2) offer = await api(`/offers/${offer.id}/submit`, undefined, 'POST');
    }
    if ([0, 1].includes(index)) {
      while (offer.status === 'PENDING_APPROVAL') {
        const step = offer.approvalSteps.find(s => s.status === 'CURRENT');
        assert(step && ['HIRING_MANAGER', 'HR_ADMIN'].includes(step.role), 'Unexpected approval requirement; no role bypass attempted');
        offer = await api(`/offers/${offer.id}/approvals/${step.id}/approve`, undefined, 'POST', step.role === 'HIRING_MANAGER' ? asManager : asHr);
      }
      if (offer.status === 'APPROVED') offer = await api(`/offers/${offer.id}/send`, undefined, 'POST');
      if (index === 1 && offer.status === 'SENT') {
        let token;
        for (let attempt = 0; attempt < 20 && !token; attempt++) {
          const result = await fetch(`http://localhost:8025/api/v1/search?query=${encodeURIComponent(`to:"${candidate.email}"`)}`);
          const messages = (await result.json()).messages || [];
          const mail = messages.find(m => m.Subject.includes('Your offer for'));
          if (mail) {
            const body = await (await fetch(`http://localhost:8025/api/v1/message/${mail.ID}`)).json();
            token = body.Text?.match(/#token=([^\s"<>]+)/)?.[1];
          }
          if (!token) await sleep(1000);
        }
        assert(token, 'Local demo offer email not ready; not bypassing candidate token checks');
        await api('/public/offers/accept', { fullName: candidate.name }, 'POST', {}, { 'X-Offer-Token': decodeURIComponent(token) });
      }
    }
    progress(`demo offer ${offerRecord.target.toLowerCase()} for candidate ${index + 1}`);
  }
  const rejected = target[6];
  if ((await app(rejected.id))?.stage === 'AI_ASSESSED') await api(`/applications/${rejected.id}/reject`, { reason: 'Synthetic demo rejection; not a real candidate decision.' }, 'POST');
  if (!manifest.completed.includes('demo-message')) {
    const conversation = await api('/conversations', { applicationId: target[4].id }, 'POST');
    await api(`/conversations/${conversation.id}/messages`, { text: 'Synthetic demo message: your local test interview has been scheduled. No real appointment or hiring commitment.', channel: 'EMAIL' }, 'POST');
    manifest.completed.push('demo-message'); checkpoint();
  }
  if (!pendingScreening) manifest.completed.push('workflow-enrichment');
  checkpoint();
  progress('normal notification, audit, talent, analytics and hire-saga consumers receive the demo workflow events');
}
