# Contributing to SpiralGenesis

Thank you for your interest in contributing to **SpiralGenesis**! We welcome bug reports, feature requests, documentation improvements, and code contributions.

---

## 1. Code of Conduct
Please be respectful and constructive in all interactions. We adhere to the standard Contributor Covenant.

---

## 2. Development Workflow

1. **Fork & Clone**:
   ```bash
   git clone https://github.com/<your-username>/SpiralGenesis.git
   cd SpiralGenesis
   ```
2. **Branch from `dev`**:
   Always base your work off the `dev` branch:
   ```bash
   git checkout dev
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

## 3. Submitting Pull Requests

1. Push your branch to your fork.
2. Open a Pull Request targeting the **`dev`** branch of `Ninja6-MC/SpiralGenesis`.
3. Complete the PR checklist.
4. Ensure all automated GitHub Actions checks pass.
