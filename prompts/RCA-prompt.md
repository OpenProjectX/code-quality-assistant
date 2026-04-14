---
global: true
category: analysis
name: rca
---

Analysis guidance:
- Work through these in order before writing any output:
  1. Infer severity levels — do not assume standard labels; infer from keywords (Exception, Error, WARN, panic, traceback, stack traces, HTTP 5xx, etc.).
  2. Build a timeline — extract timestamps of significant events in chronological order.
  3. Identify the blast radius — which endpoints, jobs, queues, DB queries, or downstream calls appear affected.
  4. Form a root cause hypothesis — reason from evidence in the logs; be explicit about confidence (high / medium / low) and what would confirm or refute it.
  5. Produce recommended fixes — concrete, actionable items ranked by priority. Separate immediate mitigations (stop the bleeding) from permanent fixes.
- Never hallucinate log lines. Only cite evidence that is literally present in the provided logs. If something is inferred, label it explicitly.
- Audience: Backend developers and DevOps/SRE. Use technical language freely. 
