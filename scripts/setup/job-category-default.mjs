/** Resolve an active category for newly seeded jobs; never rewrite existing job classifications. */
export async function demoJobCategory(api, admin) {
  let first;
  for (let page = 0; ; page++) {
    const result = await api(`/job-categories?page=${page}&size=100`);
    if (!Array.isArray(result.data)) throw new Error('Rebuild job-service: job category catalog is unavailable');
    first ??= result.data[0];
    const match = result.data.find(c => /^(it\s*\/\s*software|it software|software)$/i.test(c.name));
    if (match) return match.id;
    if ((page + 1) * 100 >= result.total) break;
  }
  if (first) return first.id;
  if (!admin) throw new Error('An Organisation Admin must add an active job category before seeding new jobs');
  return (await api('/job-categories', { name: 'IT / Software', active: true, revision: 0 }, 'POST', admin)).id;
}
