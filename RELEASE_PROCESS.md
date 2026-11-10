# SpiralGenesis release process

## Version and channels

Release tags are `vMAJOR.MINOR.PATCH`, optionally followed by `-alpha.N`,
`-beta.N` or `-rc.N`. Tags always identify a commit on `main`. Alpha maps to
GitHub prerelease, Modrinth `alpha` and Hangar `Alpha`. Beta and RC map to
GitHub prerelease, Modrinth `beta` and Hangar `Beta`. A plain version maps to
GitHub latest (when it is the newest stable release), Modrinth `release` and
Hangar `Release`.

MAJOR means incompatible API or storage changes, MINOR new functionality, and
PATCH compatible fixes. Stable releases require a `## [MAJOR.MINOR.PATCH]`
section in `CHANGELOG.md`. A prerelease may use a matching prerelease or base
version section, or a short fallback note. The GitHub release body begins with
that changelog text from the tagged commit and appends GitHub's generated
change list. The Modrinth and Hangar changelogs carry the changelog text only,
because generating the change list needs a write token that those jobs do not
hold.

## Prepare, verify, approve, promote

1. Merge the intended source to `main` and confirm its CI checks pass. For a
   stable release, merge the changelog section before starting the candidate.
2. Run **Verify Release Candidate** from `main` with input `tag` set to the
   intended tag. The tag does not exist yet: the candidate workflow is
   dispatched on `main`, before tagging, so its run carries no tag ref. What
   binds the run to the tag is the manifest it records, and Publish Release
   checks that binding (step 6). The build job runs Gradle tests, makes a
   versioned shadow JAR once, and retains it with its SHA-256 sidecar for 90
   days, the repository maximum. A manifest records the source SHA, tag,
   version, channel, workflow run ID and attempt, JAR artifact ID, complete
   filename and SHA-256 list, and GitHub/Modrinth/Hangar destinations. The
   smoke jobs download that artifact, inspect its descriptor and main class,
   and boot it on Paper and Folia at 1.20.4, 1.21.11 and 26.2, and on Paper at
   26.3. Only when all seven pass does the run retain passing evidence bound to
   the manifest digest and candidate identity.
3. Record the successful candidate run ID. Do not rerun a failed candidate:
   start a new dispatch so artifacts and attempt 1 remain unambiguous.
4. From the main checkout, run `scripts/release.sh <version>` to create and
   push the tag. Its checks require a clean, current `main`, a new tag, and a
   stable changelog section. Check that the tag points to the candidate's
   source SHA. Tag push alone does not publish.
5. Inspect the owner inventory of each registry for the exact intended
   version (see *Absence attestation*), then dispatch **Publish Release**:
   `scripts/release.sh publish <version> <candidate-run-id> --attest modrinth,hangar`.
   The script checks its inputs, asks you to confirm each attestation by
   typing the version, and runs
   `gh workflow run release.yml --ref <tag> -f tag=<tag> -f candidate_run_id=<id> -f destinations=<list> -f modrinth_absence_confirmed=<bool> -f hangar_absence_confirmed=<bool> -f nonce=<nonce>`
   for `Ninja6-MC/SpiralGenesis`. The nonce appears in the run name, so the
   run can be found again. `destinations` defaults to
   `github,modrinth,hangar`; select fewer only to retry (see the retry table).
6. The `plan` job runs first, with no environment, no publishing credentials
   and a read-only token, so its failures appear before anyone is asked to
   approve. It checks that the candidate run is a completed, successful,
   first-attempt `workflow_dispatch` run of this repository's
   `release-candidate.yml` on `main`; that its commit is the tag's commit both
   locally and on origin; and that the retained manifest names this tag, that
   commit, that run and attempt 1. It downloads the JAR, checksum, manifest and
   evidence and rechecks inventory, digests, descriptor, version, channel and
   passing test evidence. It then checks each selected destination through
   public lookups and refuses a missing attestation for any registry that is
   not publicly complete. It applies the attestation guards (see below),
   checks the changelog section, checks that each selected registry project
   resolves publicly, and dry-runs the Hangar upload's task graph from the
   retained JAR. The job summary records the run's identity, destinations,
   public state and attestations.
7. Each selected destination then publishes in its own job:
   `publish-github`, `publish-modrinth` and `publish-hangar`. All three use the
   protected `release` environment and depend on `plan` alone, so they wait at
   the gate together and one approval covers the run. The maintainer reviews
   the plan summary and approves in the browser. Automation must not approve
   or reject this gate.
8. Each publish job downloads the same retained candidate again and repeats
   the provenance and digest checks. Before writing anything, it runs the
   credential preflight for every destination selected in this dispatch, not
   only its own (see *Credentials*), so a missing or rejected credential stops
   all of them before the first public write. It reconciles its own
   destination (see *Retry and partial publication*). If the destination is
   absent, it runs the attestation guards and the digest and tag recheck
   immediately before the upload, uploads the downloaded files as they are,
   then downloads what consumers receive and compares it with the manifest.
   No publish job builds or packages anything: Hangar's Gradle task takes the
   retained JAR path and refuses any archive task in its task graph.

A failure while uploading to one destination does not stop the others. That
destination is retried later by a new dispatch that selects only it.

## Credentials

The candidate job and `plan` have `contents: read`, no registry credentials
and no public publishing step. Only `publish-github` has `contents: write`;
`publish-modrinth` and `publish-hangar` read. Each publish job also has
`actions: read` to download the candidate. `MODRINTH_TOKEN` and
`HANGAR_API_TOKEN` must exist as **environment secrets** on `release`, with
`Create versions`, `Read projects` and `Read versions` for Modrinth and
`create_version` and `edit_page` for Hangar. The Modrinth token holds those
three scopes and nothing else: never `Write versions` and never a delete scope.
The Hangar key must cover the project and belong to a project member;
reconciliation exchanges it for a session and verifies project membership.
The permission and version lookup endpoints used here check project access and
visibility; they do not require a separate `view_public_info` key permission.
Do not add equivalent repository or organisation secrets. The protected
environment requires a maintainer reviewer and tag restriction; verify those
settings in the repository UI before the first promotion.

The credential preflight in every publish job checks, for each selected
destination: GitHub, that the job token can read the repository's releases;
Modrinth, that `MODRINTH_TOKEN` is set, reads the project, finds it not
archived and reads its version list; Hangar, that `HANGAR_API_TOKEN` is set
and exchanges for a session that is a project member and holds
`create_version`, plus `edit_page` when the project page will be synced.

### Prerequisites only an upload can prove

These cannot be checked without writing. A failure in one of them is a
failure of that destination alone: the others still publish, and that
destination is retried by a new dispatch once the prerequisite is fixed.

| Prerequisite | Destination | Why the preflight cannot prove it |
| :--- | :--- | :--- |
| `MODRINTH_TOKEN` holds `Create versions` | Modrinth | A token limited to its three scopes cannot read its own scopes; they are visible only on Modrinth. |
| `MODRINTH_TOKEN` is valid at all | Modrinth | Modrinth can answer an invalid token or missing read scope with the anonymous view instead of an error. |
| Modrinth accepts the version's metadata (loaders, game versions, dependencies) | Modrinth | There is no dry-run upload. |
| Hangar accepts the version's metadata (platform versions, dependencies, channel) | Hangar | The plan dry run proves the task graph and the JAR path, not the server-side validation. |
| `contents: write` lets the job create the release | GitHub | The job token's permissions are not exposed to the job. |

## Absence attestation

Modrinth's aggregate project and version routes omit drafts, scheduled and
unlisted versions even for a member's token, and Hangar's authenticated API
does not show soft-deleted versions, which still block their version number.
A lookup that finds nothing is therefore not proof that a version is absent.
Neither registry is known to return every hidden and deleted state, so both
need an attestation; GitHub's release lookup is exact and needs none.

Before setting `modrinth_absence_confirmed` or `hangar_absence_confirmed` to
true, inspect that registry's owner dashboard for the exact intended version,
including drafts, scheduled, unlisted, hidden and deleted versions. An
attestation:

* is a dispatch input only. It is never stored in a variable, secret or file
  that outlives the run.
* is needed only for a selected registry that is not already complete. A
  complete one is verified and skipped without it. An attestation for a
  destination that is not selected is refused.
* is refused on any run attempt other than the first. The check runs in
  `plan` and again immediately before each upload, because re-running one job
  reuses the outputs of the jobs it depends on. Dispatch a new run instead.
* is refused while another Publish Release run for the same tag is in
  progress, or when one finished after this run was created, since that run
  may have written a version this inspection did not see. A run created later
  that has not started cannot have written anything and does not count; the
  concurrency group holds it until this run ends. Completion time is taken
  from the run's last update, which errs towards refusing. Inspect the
  inventory again and dispatch a new run.

Publish Release's concurrency group is keyed on `github.ref`, which is the tag
ref every publishing job requires. Every workflow able to write release
versions must share that group and be listed in `PUBLISHER_WORKFLOWS` in
`scripts/release_promotion.py`; today that is `release.yml` alone.

## Project page and latest release

A step that changes shared state rather than adding a version runs only when
the tag is the newest public version on its channel, judged by the
repository's published (non-draft) GitHub releases with the same channel.
Two steps qualify. Hangar's resource page is synced from
`docs/modrinth-description.md` after the version upload and read back for
equality. The GitHub release is marked latest only for a stable tag that is
the newest; any other release is created with `--latest=false`. Nothing in the workflow edits the
Modrinth project description; that remains a manual paste, as
`scripts/modrinth-description.py` describes.

## Registry metadata

The Modrinth publication declares optional Floodgate, GriefPrevention,
AuthMeReloaded and Multiverse-Core dependencies, matching `plugin.yml`.
Hangar declares the same optional dependencies and uses its existing
`Alpha`, `Beta` and `Release` channels. Both registries list Paper/Purpur/Folia
and the supported Minecraft versions in their publishing configuration.

Modrinth versions are uploaded unfeatured, and the publisher never features
or unfeatures a version or edits an existing one. The upload step sets
`modrinth-featured: false` and `modrinth-unfeature-mode: none`. Without the
second input, `mc-publish` defaults to unfeaturing older featured versions
after the upload, which patches them and needs `Write versions`. The
1.0.0-beta.2 publish uploaded its version, then failed at that unfeature step
with a 401, leaving a partial publication to resume. Feature a version on the
project page by hand on Modrinth if wanted; do not widen the token for it.

## Retry and partial publication

Each publish job reconciles its destination before writing. A GitHub release
is complete only if its source, prerelease flag, exact asset list and
downloaded asset digests match. A Modrinth version is complete only if its
version type and filename match and its downloaded JAR's SHA-256 matches the
manifest. Modrinth supplies SHA-1/SHA-512 metadata, not SHA-256; the
publisher computes SHA-256 from the consumer download. It also looks up the
retained candidate's SHA-512 to recover an identical nonlisted upload, which
cannot find a conflicting hidden one. A complete Modrinth version must be
`listed` and readable through the anonymous consumer API. Hangar's
authenticated version API exposes hidden versions to project members, along
with the JAR digest and download URL; the publisher compares version, channel,
filename, declared SHA-256 and the downloaded consumer JAR, and a complete
Hangar version must have `public` visibility and be readable anonymously.

Complete destinations are skipped. Absent ones receive the retained
candidate, and the job then downloads what consumers receive, retrying for
about two minutes while the registry makes it visible, and fails unless it
matches the manifest. A conflicting version, unavailable API, incomplete
asset or mismatched digest stops that destination without replacement. A
draft or scheduled Modrinth version, or a nonpublic Hangar version, stops
after byte reconciliation for manual status recovery; it is never treated as
an absent upload. Resolve any mismatched public bytes through the registry's
own support or removal process before another promotion.

| Failure | What it means | Retry |
| :--- | :--- | :--- |
| `plan` fails: candidate, tag, notes or project check | Nothing was approved or written | Fix the cause and dispatch again; a new candidate if the old one is wrong or expired |
| `plan` fails: missing attestation | A selected registry is not publicly complete | Inspect that owner inventory, then dispatch again with its attestation |
| `plan` or a guard refuses an attestation | Later attempt, or another publisher run for the tag ran or is running | Inspect the inventory again and dispatch a new run; never re-run the old one |
| Credential preflight fails in the publish jobs | Nothing was written anywhere | Fix the credential or project, then dispatch again with the same destinations |
| One upload fails (outage, rejected metadata, a prerequisite above) | The other destinations still published | Fix the cause, then dispatch selecting only that destination, attesting it again if it is still absent |
| Upload succeeded but consumer verification failed | Bytes or visibility differ, or are not visible yet | Dispatch selecting only that destination without an attestation; if it is complete it is verified and skipped, otherwise reconcile on the registry by hand |
| Hangar page sync failed after the upload | The version is published; the page is stale | Run `./gradlew syncPluginPublicationMainResourcePagePageToHangar` from the tagged source with a key holding `edit_page`, then compare the public page to `docs/modrinth-description.md`; do not dispatch a version upload for it |
| A publish job failed before its upload for a transient reason | Nothing was written by that job | Re-running that job is allowed only without an attestation for its destination; otherwise dispatch a new run |

For example, to retry Hangar alone after a Hangar outage:
`scripts/release.sh publish 1.2.3 123456789 --destinations hangar --attest hangar`,
after confirming in the Hangar dashboard that no `1.2.3` version, deleted or
otherwise, exists.

Candidate artifacts expire 90 days after the candidate run. There is no
fallback rebuild in the publisher. An expired candidate stops the publisher
and nothing is rebuilt for that tag. A destination still missing when its
candidate expires is recorded below as not published for that tag, and is
reached by the next release instead. Do not reuse a tag that points to
different code.

### Not published

| Tag | Destination | Reason |
| :--- | :--- | :--- |
| none | | |

## What the tests cover

GitHub, Modrinth and Hangar expose the published JAR or its SHA-256 for
automated comparison. The source tests run before the JAR build, while the
downloaded JAR's descriptor and class inventory and Paper/Folia server boots
are tested after retention; those smoke tests do not repeat every unit test
against bytecode extracted from the JAR.

`scripts/tests/test_release_promotion.py` covers the refusal paths: a true
attestation on a later attempt, in `plan` and before an upload; a missing
attestation for a selected destination that is not publicly complete; a
candidate run from another workflow, repository, branch, attempt, commit or
tag; a concurrent or later-finished publisher run for the same tag; and a
missing credential for any selected destination.
