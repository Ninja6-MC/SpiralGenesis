# Contributing to SpiralGenesis

Thank you for your interest in contributing to **SpiralGenesis**! We welcome bug reports, feature requests, documentation improvements, and code contributions.

---

## 1. Code of Conduct
Please be respectful and constructive in all interactions. We adhere to the standard Contributor Covenant.

---

## 2. Branch Naming

`main` is the trunk. Everything else is a short-lived branch that merges back into it
through a pull request; there is no long-running integration branch.

Name branches `<type>/<short-kebab-slug>`, where `<type>` is the same vocabulary as
[Conventional Commits](https://www.conventionalcommits.org/), so the branch and the commits
on it agree:

| Type | Use for | Example |
| :--- | :--- | :--- |
| `feat/` | New functionality | `feat/sqlite-storage` |
| `fix/` | Bug fixes | `fix/authme-double-allocation` |
| `docs/` | Documentation only | `docs/branch-naming-convention` |
| `refactor/` | Restructuring with no behaviour change | `refactor/extract-terrain-scorer` |
| `perf/` | Performance work | `perf/cache-chunk-profiles` |
| `test/` | Tests only | `test/spiral-math-edge-cases` |
| `chore/` | Build, CI, tooling, dependencies | `chore/bump-gradle-9` |

Rules:

* Lowercase, hyphen-separated, no underscores or spaces.
* Describe the change, not the author or the tool that made it — `feat/folia-scheduler`,
  never `bharath/patch-2` or a generated name like `claude/fix-77c354`.
* Keep it short. Two or three words is usually enough; the PR title carries the detail.
* One branch per logical change. If you find yourself naming it `feat/misc`, it should be
  two branches.

Automated agents and bots follow the same scheme. If a tool creates a branch under its own
default name, rename it before opening the PR:

```bash
git branch -m feat/my-actual-change
```

If the old name was already pushed, publish the new one and delete the old, or the stale
branch outlives the rename:

```bash
git push -u origin feat/my-actual-change && git push origin --delete <old-name>
```

---

## 3. Development Workflow

1. **Fork & Clone**, and add this repository as `upstream` so you can keep `main` current:
   ```bash
   git clone https://github.com/<your-username>/SpiralGenesis.git
   cd SpiralGenesis
   git remote add upstream https://github.com/Ninja6-MC/SpiralGenesis.git
   ```
2. **Branch from an up-to-date `main`**:
   ```bash
   git checkout main
   git pull upstream main   # `origin` if you are working in the repository itself
   git checkout -b feat/my-new-feature
   ```
3. **Coding Standards**:
   * Target Java 21 LTS.
   * Follow standard Java naming conventions and clean code practices.
   * Asynchronous operations must use Paper's async API (`world.getChunkAtAsync(...)`) to prevent main thread blocking.
4. **Testing**:
   Add JUnit 5 unit tests for any mathematical or logic modifications:
   ```bash
   ./gradlew test
   ```
5. **Commit Messages**:
   Follow Conventional Commits:
   * `feat(math): add hexagonal plot calculation`
   * `fix(authme): resolve race condition on delayed join`
   * `docs(readme): add bStats configuration instructions`
6. **Sign Off Every Commit**:
   ```bash
   git commit -s -m "fix(authme): resolve race condition on delayed join"
   ```
   `-s` appends a `Signed-off-by` line. See [section 5](#5-licensing-of-contributions)
   for what signing off means. CI checks this on every pull request.

---

## 4. Submitting Pull Requests

1. Push your branch to your fork.
2. Open a Pull Request targeting the **`main`** branch of `Ninja6-MC/SpiralGenesis`.
3. Complete the PR checklist.
4. Ensure all automated GitHub Actions checks pass. `main` is protected: CI must be green
   and the branch up to date before it can merge.
5. PRs are squash-merged, so the PR title becomes the commit on `main` — write it as a
   Conventional Commit.

---

## 5. Licensing of Contributions

SpiralGenesis is [GPL-3.0](LICENSE), and every contribution is published under that
licence. You keep the copyright in what you write; nothing here assigns it away.

Signing off a commit means two things:

1. You certify the [Developer Certificate of Origin 1.1](https://developercertificate.org/)
   - in short, that you wrote the contribution or otherwise have the right to submit it
   under the project's licence.
2. You grant Ninja6-MC a perpetual, worldwide, non-exclusive, royalty-free and irrevocable
   licence to use, reproduce, modify and distribute your contribution, **including under
   licence terms other than GPL-3.0**.

Point 2 is the part that goes beyond a plain DCO, so it is worth being direct about why it
is here. It keeps the project able to change its licence, or to offer the same code under
separate terms, without having to track down and get agreement from every past contributor,
which is impossible in practice once a project has been around for a while. It does not
take anything away from you: your contribution still ships publicly under GPL-3.0, and you
remain free to use your own work however you like.

Add the sign-off with `-s`:

```bash
git commit -s -m "fix(spawn): reject candidates adjacent to powder snow"
```

which appends a line to the commit message:

```
Signed-off-by: Your Name <your.email@example.com>
```

Use your real name and an email you can be reached at. The
[DCO check](.github/workflows/dco.yml) fails a pull request if any commit is missing the
line. To fix the most recent commit:

```bash
git commit --amend -s --no-edit
```

To fix a whole branch, rebase against `main` with sign-off applied to every commit:

```bash
git rebase --signoff origin/main
```

Both rewrite history, so force-push the branch afterwards.
