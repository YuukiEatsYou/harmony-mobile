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
