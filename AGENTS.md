# AGENTS.md

Guidance for AI coding agents working in the Harmony Mobile repository.
`README.md` is authoritative for how the app is built and released, and
`UPSTREAM_CHANGES.md` records what this client needs from Harmony. Read both
before you change anything here.

## Checks

Requires Node >= 20.

```sh
npm run typecheck   # tsc
npm run test        # plain-Node tests for the address and URL logic
npm run check       # typecheck + build
```

There is no linter. A change is not done on the strength of reading it: run the
checks that apply, and for the parts that only work in the app (opening an
instance, adding a server against a real instance) test them on a device or an
emulator. Say what you could not verify rather than implying it works.

## Git workflow: fork, branch, pull request

This repository is a GitHub **fork -> branch -> pull request** repository. The
company fork is ours to write to; the canonical repository is read-only for the
company account. Every change lands through a branch and a pull request against
`upstream/main`. The full command reference, the account handle and the one-time
setup live in `docs/GIT_WORKFLOW.md`.

- `origin` - the company fork, `https://github.com/hrmnychat-hue/harmony-mobile`
  (GitHub account `hrmnychat-hue`). This is the only remote we push to.
- `upstream` - the canonical repository,
  `https://github.com/YuukiEatsYou/harmony-mobile`. Pull access only; never push
  to it.

Start every piece of work on a branch off `upstream/main`, fetching it first so
the branch is current (`git fetch upstream && git switch -c <branch>
upstream/main`). Name the branch after the version it is expected to ship as,
`v<version>` (for example `v0.2.0`; the version comes from the release tag, see
`README.md` -> Releasing). A change that ships no user-visible behavior, such as
a docs edit, uses `docs/<slug>` or `chore/<slug>` instead.

Push the branch to the fork (`git push -u origin <branch>`), then open a pull
request against `upstream/main` through the API, because the company account
cannot target the canonical repository from the web UI (`GITHUB_TOKEN` is
already in the agent environment). Never force-push, and never commit to `main`
on either remote.

The owner (`YuukiEatsYou`) reviews and merges pull requests into `upstream`
`main`. The company account has pull-only access upstream, so agents cannot merge
and cannot push upstream. Do not merge your own pull request; push review fixes
as new commits on the same branch and re-request review. After a merge, delete
the branch on the fork and locally, then rebuild local `main` from
`upstream/main` (`git fetch upstream && git switch main && git merge --ff-only
upstream/main`).

### Upstream publication rule (YUC-RULE-003)

No change may dead-end on a local branch. A task that changes this repository is
not done until its branch is pushed to the fork and a pull request is open
against `upstream/main`. Publish before you call the task done and record the
branch name and the pull request URL on the task.

- Opening the pull request is ours; merging is the owner's, so waiting on a merge
  is a valid `in_review`. A branch that exists only on the author's machine is a
  dead end and is never a finished state.
- If the change cannot be published yet - no push access, an unmerged
  dependency, a build or review still pending - block the task with a named
  owner and the exact next action instead of reporting completion.
- A run that ends early does not drop the work: the next run publishes the
  branch, or retires it explicitly with a recorded reason.
- Never silently delete another agent's unpublished branch. Publish it, or
  retire a superseded branch on purpose with the reason recorded and flag it to
  the CTO.

## Clean workspace: leave nothing running

Company rule YUC-RULE-001. Every employee leaves their workspace exactly as they
found it. When a task ends - success, failure, or timeout - nothing you started
is still running, and nothing you created outside the task's execution workspace
is left behind. RAM is finite and shared.

- Stop everything you started before you mark a task done: dev/preview/test
  servers, watchers, app servers, emulators, `adb`, Gradle/Kotlin daemons,
  databases, queues, tunnels, and backgrounded scripts.
- Prefer Paperclip-managed issue-workspace runtime services over `npm run dev &`;
  they are tracked and stoppable by others.
- Reap what you background - kill the process group, do not rely on the shell
  exiting.
- Leave no extra copies: throwaway clones, worktrees, temp data dirs, AVDs.
- Verify before you finish (`ss -ltnp`, `ps -eo pid,etime,rss,args`,
  `adb devices`, `./gradlew --stop`) and put the result in your completion note.
- If a run dies, the next run reaps orphans attributable to finished tasks.
- Never kill someone else's live work - flag it to the CTO.
