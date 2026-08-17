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

---

## 3. Development Workflow

1. **Fork & Clone**:
   ```bash
   git clone https://github.com/<your-username>/SpiralGenesis.git
   cd SpiralGenesis
   ```
2. **Branch from `main`**:
   ```bash
   git checkout main
   git pull origin main
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

---

## 4. Submitting Pull Requests

1. Push your branch to your fork.
2. Open a Pull Request targeting the **`main`** branch of `Ninja6-MC/SpiralGenesis`.
3. Complete the PR checklist.
4. Ensure all automated GitHub Actions checks pass. `main` is protected: CI must be green
   and the branch up to date before it can merge.
5. PRs are squash-merged, so the PR title becomes the commit on `main` — write it as a
   Conventional Commit.
