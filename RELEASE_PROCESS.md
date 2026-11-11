# SpiralGenesis release process

## Version and channels

Release tags are `vMAJOR.MINOR.PATCH`, optionally followed by `-alpha.N`,
`-beta.N` or `-rc.N`. Tags always identify a commit on `main`. Alpha maps to
GitHub prerelease, Modrinth `alpha` and Hangar `Alpha`. Beta and RC map to
GitHub prerelease, Modrinth `beta` and Hangar `Beta`. A plain version maps to
GitHub latest (when it is the highest stable version), Modrinth `release` and
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
   Select `shared-state` alone to recover the Hangar page and GitHub latest.
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
   the gate together. A run needs one approval for these publish jobs, a
   second `release` approval for shared state after they finish when plan
   selects an update, and a third (`modrinth-description`) only when a paste
   is needed. The maintainer reviews the summaries and approves in the browser.
   Pending shared-state approval holds the shared group: approve or reject it
   promptly. Rejection leaves the page and latest marker unwritten; recover
   with a `shared-state`-only dispatch for the owning tag. Automation must
   not approve or reject any gate.
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
and no public publishing step. `publish-github` and `shared-state` have
`contents: write`; `publish-modrinth` and `publish-hangar` read. Each
publisher and the description check also has `actions: read` to download the
candidate. `MODRINTH_TOKEN` and
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
For `shared-state`, it requires the Hangar key, project membership and
`edit_page` when this tag has a complete Hangar version; it does not upload a
version or require `create_version`. GitHub read access is preflighted too.
Keep this key in the `release` environment for page retries too.

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
  may have written a version this inspection did not see. A run created
  after this one does not count, whatever its status: it shares this run's
  concurrency group, so it cannot start while this run holds the group and
  cannot have written anything. That includes a later run cancelled while
  queued for the group, which shows as completed with a recent update.
  Completion time is taken from the run's last update, which errs towards
  refusing. Inspect the inventory again and dispatch a new run.

Publish Release's concurrency group is keyed on `github.ref`, which is the tag
ref every publishing job requires. Every workflow able to write release
versions must share that group and be listed in `PUBLISHER_WORKFLOWS` in
`scripts/release_promotion.py`; today that is `release.yml` alone.

## Shared-state ownership and recovery

Hangar's project page belongs to the highest public Release-channel version
on Hangar. Until Hangar has a stable release, its highest public pre-release
owns the page. Modrinth's page follows the same rule using its own listed
public versions. The GitHub latest marker belongs only to the highest
published stable GitHub release; it remains unset until the first stable.
Versions are ordered by SemVer, including numeric pre-release identifiers.
A stable hotfix owns its destination's page even after a higher pre-release;
a pre-release cannot replace a stable page. These inventories are independent:
a missing upload on one destination does not make GitHub its authority.

`plan` records the current owners and predicts which selected uploads can
own shared state. Modrinth-only dispatches and tags that cannot own either
selected destination skip the shared-state job before its gate. Recovery-only
plans consider only already-complete public destinations. The plan decision
can only skip work; it never authorizes a write. Each GitHub release is created
with `--latest=false`. After the publish jobs finish, `shared-state` runs under
`environment: release`, with the same tag-ref guard, using the job-level
`release-shared-state` concurrency group with `cancel-in-progress: false`.
The group is repository-wide and does not contain the tag. This is a second
`release` approval after the publish jobs, not part of their first approval.
While that approval is pending it holds the shared group; approve or reject
it promptly. Rejecting it leaves the page and latest marker unwritten.
Recover with a `shared-state`-only dispatch for the owning tag. A read-only
report job records skipped and rejected results and the recovery guidance
in the run summary. The shared-state job downloads
and verifies the retained candidate again and preflights its credentials.
Immediately before each write it rechecks the tag, evidence and digests,
verifies that destination's public version matches the retained bytes, then
reads ownership again. A non-owner skips with its owner's tag in the log.
Each selected shared-state write runs independently, so a failed GitHub
latest update does not skip an eligible Hangar sync; either failure still
fails the job. Only an owner calls `gh release edit --latest` or syncs the Hangar page from
`docs/modrinth-description.md`. Hangar's sync reads the public page back for
exact equality. This job contains no build or packaging step.

To recover shared state without adding a version, select `shared-state`
alone with the original candidate run ID and both attestations false. This
replaces the former `hangar-page` option and repairs both the Hangar page and
GitHub latest for the tag where it owns them. The tag must have at least one
complete published GitHub or Hangar version. Incomplete bytes fail closed;
an absent destination is skipped, never uploaded. A complete Modrinth version
also makes its description eligible for the separate check below.
The same 90-day candidate retention prerequisite applies.

GitHub keeps one running and one pending job in a concurrency group. A third
queued shared-state job cancels the pending one even with
`cancel-in-progress: false`; ordering is not guaranteed. A cancelled pending
job has made no write. Every surviving job rechecks ownership under the lock,
so a non-owner cannot replace state while attempting recovery. Cancellation
can still leave a page stale or the latest marker unset, especially if the
owner's pending job was replaced. Re-run the `shared-state` dispatch for the
owning tag on each affected destination. A tag can own Hangar and GitHub
independently, so this can require two dispatches, run one at a time.
Do not widen the concurrency group to the manual description approval.

Preview the recovery dispatch without writing anything:

```bash
scripts/release.sh publish 1.2.3 123456789 --destinations shared-state --dry-run
```

```powershell
scripts\release.ps1 publish 1.2.3 123456789 --destinations shared-state --dry-run
```

## Modrinth description paste and confirmation

`docs/modrinth-description.md` is generated from `README.md` by
`python scripts/modrinth-description.py`; the existing CI description check
fails when it is stale. The expected body strips the leading generated
comment and surrounding blank lines exactly like Hangar's provider in
`build.gradle.kts`. Comparison normalizes line endings and trailing whitespace
only; indentation and other content differences remain significant.

`check-modrinth-description` runs after the Modrinth publish job, with no
secrets and no environment. It runs only for a tag selected for Modrinth or
already complete there, and only compares the page when that tag owns it.
It reads the public project `body` anonymously from
`https://api.modrinth.com/v2/project/spiralgenesis`. A matching body ends the
step without approval. Otherwise the run summary links to the file at the tag
and asks the maintainer to paste it into the Modrinth description editor,
omitting the generated comment, then approve `modrinth-description`. The
summary warns that a newer owner found at approval replaces this tag.

The **`modrinth-description` environment must exist**, with the maintainer as
required reviewer, **no secrets**, and custom deployment policies permitting
only `v*` tags (no branch or broader tag policy). Before requesting a paste,
the check job uses its read-only job token to read the environment and every
policy page. Missing protections, an absent environment, or a token unable
to read these APIs fail closed with no paste output and no confirm job.
The runtime job-token access has not been verified by a workflow dispatch.
Configure the environment before the first release; this change never
creates it or calls any settings write API. Automation must
not approve this gate. Neither description job receives `MODRINTH_TOKEN`,
and that token retains exactly its three upload-only scopes.

`confirm-modrinth-description` runs after approval, outside the shared-state
concurrency group. The manual paste is the write; the job only reads. It
rechecks Modrinth ownership and compares the body with the committed file at
the owner tag now. If the original tag was superseded it names the new owner
and checks that owner's file, not the waiting tag's file. A mismatch fails
with a short diff and links the precise file to paste. Paste that file, then
re-run the confirm job. The job rejects a moved owner tag and checks ownership
again after the read, retrying if it changed during comparison.

Modrinth's server-side body normalization is not yet verified. It will be
proven only on the first real release; the current tests mock the public body
and establish only the documented local normalization and owner checks.

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
The upload sets `retry-attempts: 0`: a lost response must be reconciled by a
new dispatch rather than uploading again within the same run.

## Retry and partial publication

Each publish job reconciles its destination before writing. A GitHub release
is complete only if its source, prerelease flag, exact asset list and
downloaded asset digests match. A draft for the tag stops promotion with
its name and URL or release ID, before any second create. Open the repository's
Releases page, inspect and delete that draft manually, then dispatch again.
The publisher never deletes a draft or treats its assets as verified public
bytes. `publish-github`, whose token has `contents: write`, can see drafts
in the authenticated release inventory when the tag lookup returns no
release; `shared-state` uses the same check with its write-capable token. The read-only `plan` token cannot see drafts before approval;
its summary says so, and `publish-github` checks them before any create.
A Modrinth version is complete only if its version type and filename match
and its downloaded JAR's SHA-256 matches the manifest. Modrinth supplies SHA-1/SHA-512 metadata, not SHA-256; the
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
| GitHub draft detected for the tag | A failed create left an unpublished release | Inspect and delete the named draft manually on the Releases page, then dispatch `github` again; nothing is deleted automatically |
| One upload fails (outage, rejected metadata, a prerequisite above) | The other destinations still published | Fix the cause, then dispatch selecting only that destination, attesting it again if it is still absent |
| Upload succeeded but consumer verification failed | Bytes or visibility differ, or are not visible yet | Dispatch selecting only that destination without an attestation; if it is complete it is verified and skipped, otherwise reconcile on the registry by hand. For Hangar, also dispatch `shared-state` alone without attestations once the version is complete, for the tag that owns each destination |
| Shared-state job cancelled or latest marker update failed | Versions are unchanged but shared state can be stale | Dispatch `shared-state` for the owning tag on the affected destination, with no attestations |
| Hangar page sync failed after the upload | The version is published; the page is stale | Dispatch `shared-state` alone with the original candidate run ID and no attestations; non-owners are skipped after the ownership recheck |
| Modrinth description environment check fails | Environment, reviewers, v* restriction or read access is missing | Configure the environment manually, then re-run the check job; no paste or confirm approval was requested |
| Modrinth description confirmation fails | The public body differs from its current owner file | Paste the file linked in the job summary, then re-run only the confirm job |
| A publish job failed before its upload for a transient reason | Nothing was written by that job | Re-running that job is allowed only without an attestation for its destination; otherwise dispatch a new run |

For example, to retry Hangar alone after a Hangar outage:
`scripts/release.sh publish 1.2.3 123456789 --destinations hangar --attest hangar`,
after confirming in the Hangar dashboard that no `1.2.3` version, deleted or
otherwise, exists.

The concurrency group keeps only one run queued behind the running one per
tag: a second dispatch for the same tag replaces a run still queued for the
group (status `pending`), and GitHub cancels the replaced run. A run waiting
at the `release` environment gate holds the group and is not replaced. To retry two destinations, use one dispatch that selects both,
for example `--destinations modrinth,hangar`, or wait for the first run to
finish before starting the next.

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
missing credential for any selected destination; shared-state recovery
prerequisites and attestations; GitHub drafts stopping a second create;
stable-first destination ownership, pagination and the recheck before each
write. `scripts/tests/test_release_description.py` covers matching and
differing bodies, whitespace normalization, non-owners and a waiting tag
superseded by a newer owner.
