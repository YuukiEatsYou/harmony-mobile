# Git workflow: fork, branch, pull request

Harmony Mobile is developed through a company **fork** of the canonical
repository. The company account writes to the fork; the canonical repository is
read-only. Every change lands by pushing a branch to the fork and opening a pull
request against `upstream/main`.

This file records the account and the exact commands. `AGENTS.md` owns the
conventions; this is the reference they point at.

## The two repositories

| Role | Remote | Repository | Access |
| --- | --- | --- | --- |
| Company fork (push target) | `origin` | `https://github.com/hrmnychat-hue/harmony-mobile` | read + write |
| Canonical (read + PR base) | `upstream` | `https://github.com/YuukiEatsYou/harmony-mobile` | read only |

- Company GitHub account handle: **`hrmnychat-hue`**.
- Fork URL: `https://github.com/hrmnychat-hue/harmony-mobile`.
- The fork is a real GitHub fork of `YuukiEatsYou/harmony-mobile`, so GitHub
  accepts a pull request whose head is a branch on the fork and whose base is
  `upstream/main`.

## One-time setup

If you cloned the canonical repository directly, repoint the remotes once:

```sh
git remote rename origin upstream
git remote add origin https://github.com/hrmnychat-hue/harmony-mobile.git
git remote set-url --push upstream no_push   # pull-only; we have no write access upstream
git fetch upstream
git remote -v   # origin = fork, upstream = canonical
```

Authenticate `git` without persisting the token. The company account's token is
provided to agents as the environment variable `GITHUB_TOKEN`. Point a local
credential helper at that variable; it stores the helper command, never the
secret:

```sh
git config --local credential.helper \
  '!f() { if test "$1" = get; then printf "username=x-access-token\npassword=%s\n" "$GITHUB_TOKEN"; fi; }; f'
```

Never put the token in a remote URL, a committed file, a comment, or a document.
The token is already present in the agent environment; nothing else is needed.

## Day-to-day

```sh
# 1. Start current, on a branch off the canonical main.
git fetch upstream
git switch -c <branch> upstream/main

# 2. Make the change and commit.

# 3. Push the branch to the fork.
git push -u origin <branch>
```

Branch names: `v<version>` for user-facing work (for example `v0.2.0`); `docs/`
or `chore/<slug>` for a change that ships no version and no user-visible
behavior. Unlike Harmony, the mobile version is not a constant in the tree: the
release tag (`vX.Y.Z`) is the source of truth. See `README.md` -> Releasing.

## Open the pull request

Base: `YuukiEatsYou/harmony-mobile:main`. Head: `hrmnychat-hue:<branch>`. Open it
through the API, because the company account cannot target the canonical repo
from the web UI:

```sh
curl -sS -X POST \
  -H "Authorization: Bearer $GITHUB_TOKEN" \
  -H "Accept: application/vnd.github+json" \
  https://api.github.com/repos/YuukiEatsYou/harmony-mobile/pulls \
  -d '{"title":"<title>","head":"hrmnychat-hue:<branch>","base":"main","body":"<body>"}'
```

Write the body for a reviewer who has not read the diff:

```md
## Summary
- What changed and why.

## Implementation notes
- Trade-offs, native/permission implications, anything non-obvious.

## Verification
- The exact commands you ran and their result.

## Risk and rollback
- What breaks on revert and how to revert cleanly.
```

The bundled `github-pr-workflow` skill has the full conventions (title shape,
verification evidence, replying to review comments).

## CI

Every pull request and every push to `main` runs
`.github/workflows/android.yml`: `npm run typecheck`, `npm test`, `npm run
build`, `npx cap sync android`, and a debug APK build that is uploaded as a run
artifact. A `v*` tag additionally builds and publishes a signed release APK. See
`README.md` -> Releasing.

## Review and merge

- The owner (`YuukiEatsYou`) reviews and merges pull requests into `upstream`
  `main`. The company account has pull-only access upstream, so agents cannot
  merge and cannot push upstream.
- Do not merge your own pull request. Push review fixes as new commits on the
  same branch, then re-request review.
- After a merge, delete the branch on the fork and locally, and rebuild local
  `main` from `upstream/main`:

  ```sh
  git fetch upstream
  git switch main
  git merge --ff-only upstream/main
  ```
