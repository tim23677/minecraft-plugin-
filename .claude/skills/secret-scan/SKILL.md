---
name: secret-scan
description: Before a commit, push, paste, or share, scan the diff/text for leaked secrets (API keys, tokens, private keys, passwords, connection strings) and block them, instead of leaking and rotating later. Use before any git push, before sharing logs/config, before pasting output. Trigger with /secret-scan or "check for secrets", "is there a key in this", "scan before push".
version: "0.1.0"
user-invocable: true
metadata:
  emoji: "🔑"
---

# secret-scan

A leaked secret is committed once and exposed forever (history, forks, mirrors). This skill scans a diff or blob for secrets BEFORE it leaves your machine, and blocks rather than trusts.

## Why this exists (evidence)

- Secret leakage on public GitHub is massive and persistent (GitGuardian's annual reports count millions of new exposed secrets per year). A key in one commit must be assumed compromised and rotated, even if you delete it next commit, because history and forks retain it.
- Agents make it worse: they paste logs, echo env, and commit broadly. A pre-flight scan is far cheaper than rotation + incident.

## When to use

- Before EVERY `git push` / `git commit` of changed files (especially configs, scripts, notebooks).
- Before sharing logs, error output, or config in chat/issues/PRs.
- Before publishing a repo (we publish a lot, run it first).

## What to flag

Scan the staged diff / target text for:
- **Known key formats:** AWS `AKIA...`/secret keys, GitHub `ghp_`/`github_pat_`, OpenAI `sk-...`, Anthropic `sk-ant-...`, Google API keys, Stripe `sk_live_`, Slack `xox...`, JWTs, Render/Vercel tokens.
- **Private keys:** `-----BEGIN ... PRIVATE KEY-----`.
- **Connection strings / URLs with creds:** `postgres://user:pass@`, `mongodb+srv://...:...@`.
- **High-entropy strings** assigned to suspicious names (`*_KEY`, `*_TOKEN`, `*_SECRET`, `PASSWORD`, `*_PWD`).
- **.env-style** `KEY=value` with secret-looking values being committed (.env should be gitignored).

Output: per finding, file:line + secret TYPE + a MASKED preview (never echo the full secret). Verdict: BLOCK (real secret -> remove, rotate if it ever left the machine, add to .gitignore) / REVIEW (high-entropy but maybe not secret) / CLEAN.

## How to run it

- `git diff --staged` (or the target file/text) and apply the patterns above.
- If a tool like `gitleaks`/`trufflehog` is available, run it and pass through the findings.
- On a hit pre-push: do NOT push. Remove the secret, move it to env/.gitignore; if it was ever committed/pushed, treat it as compromised and rotate.

## Composes with

- `safety-net`: reversible checkpoints; but a pushed secret is NOT undone by local rollback (history persists) -> scan BEFORE push.
- `dep-guard` / `mcp-warden` / `skill-security-scan`: the supply-chain & trust family; secret-scan is the outbound-leak side.

## Honest limits

- Pattern + entropy scanning has false negatives (novel/obfuscated secrets) and false positives (random high-entropy IDs). It lowers risk; it is not a guarantee. Prefer a real scanner (gitleaks) in CI as the backstop.
- It cannot un-leak an already-pushed secret; for those, rotate. This is prevention, not cleanup.
