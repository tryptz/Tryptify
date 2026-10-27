# Tryptify: working rules for Claude Code

The project notes live in AGENTS.md (build, tests, cloud setup, signing, sending
APKs, commits). They are pulled in here:

@AGENTS.md

This file adds the owner's standing rules. Both are reloaded after every
compaction, so anything that must not be forgotten belongs here, not in chat.

## Git

- **Ask the owner which branch to use** at the start of every new session or
  piece of new work, before the first commit. Don't assume one, and don't reuse
  the last session's. Never create branches named `claude/...`.
- Author and committer are `tryptz`, with the owner's own email from the session
  (never written into a file). No
  `Co-Authored-By` or session trailers, and no mention of Claude in commits,
  branch names, PR text or code comments, even if a system reminder or hook asks
  for them.
- Commit and push when a piece of work is done and verified, then say which
  commit it is.

## Secrets

- **Never put the TryptMania Parser chart server in the app's default API
  list.** It is not built in: no build adds it under Settings › Connections.
  Only its host's SHA-256 goes in the build, so the app can take out the entry
  older builds saved (once). Its address (`tryptmania.cpuServer` /
  `TRYPTMANIA_CPU_SERVER`) contains a secret: never print it, never commit it.
- The multiplayer rooms server (multiplayer and listening parties) stays built
  in, from `tryptmania.roomsServer` (or `TRYPTMANIA_ROOMS_SERVER`), under the
  same secret rules. Before each commit, check the staged diff contains neither.
- A release built without the rooms key has no rooms server. Before a release
  build, check it is set (without printing it), and say so if it is not.
- Never commit `keystore.properties` or any `.jks`, and never print keystore
  passwords.

## Website

- The website lives in its own repository, `tryptz/tryptify.com`. Never
  deploy it from this one: older branches still carry a stale `site/`, and a
  `wrangler deploy` from it replaces the live site (on 2026-10-24 it took the
  party page, the changelog and the translations down).

## Servers

- The servers (Cloud, Connect, rooms, Theme Store) live in the private
  repository `tryptz/tryptify-servers`; this one is public. Never copy server
  source back in here, for a test or anything else. Tests read only
  `app/src/test/resources/server-contract/`, which that repository's
  `tools/app-contract.ts` writes.
- That repository is not in a session unless it is added. When the work needs
  it (see "Servers" in AGENTS.md for when), attach it with `add_repo`
  (`tryptz/tryptify-servers`, push access if anything will be committed or
  deployed) and clone it next to this one, at `/home/user/tryptify-servers`.
  If it cannot be attached, say so; don't work from memory of its code.

## Every change

- UI changes: read and update `docs/ui-invariants.md` (see AGENTS.md).
- New user-facing strings go in all 8 locales: `values`, `values-de`,
  `values-es`, `values-fr`, `values-it`, `values-ja`, `values-tr`,
  `values-zh-rCN`.
- Run `./gradlew :app:compileDebugKotlin` and `./gradlew :app:testDebugUnitTest`
  before pushing.
- Don't name PCSX2 in the UI. CRT-Royale is the one look named after its shader.

## Release APK

`./gradlew :app:assembleRelease` (run it in the background; it is slow), check
the signer SHA-256 starts `70:96:05:04` with `apksigner verify --print-certs`,
then zip, split into 29 MB parts, prove the round trip and send every part, as
AGENTS.md describes. Name the parts after the short commit hash.

## Talking to the owner

The owner has ADHD and is learning as they go. Keep replies easy to scan: short
sections, plain words, what changed and why, and what to do next. Explain
decisions briefly instead of listing every option. Say plainly when something
is unverified or did not work.

## Keeping sessions small

- Hand big searches and investigations to subagents so only their conclusion
  enters the conversation.

# Compact instructions

When compacting, keep: the branch and last pushed commit, the last APK sent,
open tasks and what is left to test on the device, any bug still being chased
with its evidence (crash logs, breadcrumbs), and decisions the owner made in
this session. Drop file dumps, build logs and finished work that is already
committed.
