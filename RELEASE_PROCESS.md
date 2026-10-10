# SpiralGenesis release process

## Version and channels

Release tags are `vMAJOR.MINOR.PATCH`, optionally followed by `-alpha.N`,
`-beta.N` or `-rc.N`. Tags always identify a commit on `main`. Alpha maps to
GitHub prerelease, Modrinth `alpha` and Hangar `Alpha`. Beta and RC map to
GitHub prerelease, Modrinth `beta` and Hangar `Beta`. A plain version maps to
GitHub latest, Modrinth `release` and Hangar `Release`.

MAJOR means incompatible API or storage changes, MINOR new functionality, and
PATCH compatible fixes. Stable releases require a `## [MAJOR.MINOR.PATCH]`
section in `CHANGELOG.md`. A prerelease may use a matching prerelease or base
version section, or a short fallback note. Release notes begin with that
changelog text from the tagged commit and append GitHub's generated change
list.

## Prepare, verify, approve, promote

1. Merge the intended source to `main` and confirm its CI checks pass. For a
   stable release, merge the changelog section before starting the candidate.
2. Run **Verify Release Candidate** from `main` with input `tag` set to the
   intended tag. The build job runs Gradle tests, makes a versioned shadow JAR
   once, and retains it with its SHA-256 sidecar for 30 days. A manifest
   records the source SHA, tag, version, channel, workflow run ID and attempt,
   JAR artifact ID, complete filename and SHA-256 list, and GitHub/Modrinth/Hangar
   destinations. The smoke jobs download that artifact, inspect its descriptor
   and main class, and boot it on Paper and Folia at 1.20.4, 1.21.11 and 26.2,
   and on Paper at 26.3.
   Only when all seven pass does the run retain passing evidence bound to the
   manifest digest and candidate identity.
3. Record the successful candidate run ID. Do not rerun a failed candidate:
   start a new dispatch so artifacts and attempt 1 remain unambiguous.
4. From the main checkout, run `scripts/release.sh <version>` to create and
   push the tag. Its checks require a clean, current `main`, a new tag, and a
   stable changelog section. Check that the tag points to the candidate's
   source SHA. Tag push alone does not publish.
5. Dispatch **Publish Release** on the new tag ref with the exact tag and
   candidate run ID, for example
   `gh workflow run release.yml --ref v1.2.3 -f tag=v1.2.3 -f candidate_run_id=123456789 -R Ninja6-MC/SpiralGenesis`.
   For a new Modrinth upload, first check the private project dashboard for
   the exact intended version, including drafts, scheduled and unlisted
   versions. Only if none exists, include
   `-f modrinth_absence_confirmed=true`. The default is false. This manual
   confirmation applies only to this tag, source, candidate and first attempt
   of this dispatch; a rerun cannot reuse it. The preapproval job summary
   records that identity and confirmation for the maintainer to review.
   Hangar's authenticated API also hides soft-deleted versions. Check its
   private dashboard, including deleted versions, and include
   `-f hangar_absence_confirmed=true` only when this intended version does not
   exist. This confirmation has the same identity and attempt restrictions.
   The run waits for the protected `release` environment. The
   maintainer reviews the candidate manifest, evidence and declared
   destinations and approves it in the browser. Automation must not approve
   or reject this gate.
6. The publisher checks that the run succeeded on `main`, that all three
   retained artifacts are present and unexpired, and that the tag still
   resolves to the candidate source both locally and on origin. It downloads
   the JAR, checksum, manifest and evidence, rechecks inventory, digest, descriptor,
   version, channel and passing test evidence, and repeats the check before
   each destination. It publishes the downloaded JAR to GitHub, Modrinth and
   Hangar in that order. Hangar's Gradle task takes the retained JAR path and
   refuses any archive task in its task graph.

The candidate and verifier jobs have `contents: read`, no registry credentials
and no public publishing step. Only the `release` environment job has
`contents: write`. `MODRINTH_TOKEN` and `HANGAR_API_TOKEN` must exist as
**environment secrets** on `release`, with `Create versions`, `Read projects`
and `Read versions` for Modrinth and `create_version` and `edit_page` for
Hangar. The Hangar key must cover the project and belong to a project member;
reconciliation exchanges it for a session and verifies project membership.
The permission and version lookup endpoints used here check project access and
visibility; they do not require a separate `view_public_info` key permission.
Do not add equivalent repository
or organisation secrets. The protected environment requires a maintainer
reviewer and tag restriction; verify those settings in the repository UI before
the first promotion. The publisher fails if either token is absent.

The Modrinth publication declares optional Floodgate, GriefPrevention,
AuthMeReloaded and Multiverse-Core dependencies, matching `plugin.yml`.
Hangar declares the same optional dependencies and uses its existing
`Alpha`, `Beta` and `Release` channels. Both registries list Paper/Purpur/Folia
and the supported Minecraft versions in their publishing configuration.
Hangar's resource page is synced from `docs/modrinth-description.md` after
the version upload and read back for equality.

## Retry and partial publication

Candidate artifacts expire 30 days after the candidate run. There is no
fallback rebuild in the publisher. A missing or expired JAR, manifest or
evidence requires a new candidate from the intended source and a new approval.
Do not reuse a tag that points to different code.

Dispatch **Publish Release** again with the same tag and candidate run ID to
resume a failed run. Before writing, it checks existing destinations. A GitHub
release is complete only if its source, prerelease flag, exact asset list and
downloaded JAR digest match. A Modrinth version is complete only if its
version type and filename match and its downloaded JAR's SHA-256 matches the
manifest. Modrinth supplies SHA-1/SHA-512 metadata, not SHA-256; the publisher
computes SHA-256 from the consumer download. It also looks up the retained candidate's
SHA-512 to recover an identical nonlisted upload. Aggregate project/version
APIs omit drafts and unlisted versions even for members; a hash lookup cannot
find a conflicting hidden upload. No matching version therefore stops unless
the maintainer explicitly confirmed absence in the private dashboard for this
dispatch. This is a manual reconciliation input, not automatic absence
verification. Modrinth can silently fall back to anonymous visibility for an
invalid token or missing read scopes; the dashboard check must use the
maintainer's project-member session. A complete Modrinth version must be
`listed` and readable through the anonymous consumer API. Complete destinations are
skipped; absent ones receive the retained candidate. A conflicting version,
unavailable API, incomplete asset or mismatched digest stops the run without
replacement.

Hangar's authenticated version API exposes hidden versions to project members,
along with the JAR digest and download URL. The publisher compares version,
channel, filename, declared SHA-256 and the
downloaded consumer JAR. A mismatch stops for manual reconciliation. If a
Hangar page sync alone failed, run
`./gradlew syncPluginPublicationMainResourcePagePageToHangar` from the tagged
source with a Hangar key carrying `edit_page`, then compare the public page to
`docs/modrinth-description.md`. Do not rerun a version upload against a
present Hangar version. A draft or scheduled Modrinth version, or a nonpublic
Hangar version, stops after byte reconciliation for manual status recovery;
it is never treated as an absent upload. Missing credentials, authentication
failures and uncertain private-version access also stop before any upload.
Hangar's authenticated 404 cannot rule out a soft-deleted version. It requires
the explicit private-dashboard absence confirmation before uploading, even
after project membership is verified. A complete Hangar version must have
`public` visibility and be readable through the anonymous consumer API.
Resolve any mismatched public bytes through the
registry's own support or removal process before another promotion.

GitHub, Modrinth and Hangar expose the published JAR or its SHA-256 for
automated comparison. The source tests run before
the JAR build, while the downloaded JAR's descriptor and class inventory and
Paper/Folia server boots are tested after retention; those smoke tests do not
repeat every unit test against bytecode extracted from the JAR.
