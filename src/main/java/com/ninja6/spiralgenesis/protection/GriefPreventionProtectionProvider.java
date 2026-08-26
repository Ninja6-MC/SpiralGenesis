package com.ninja6.spiralgenesis.protection;

import com.ninja6.spiralgenesis.config.ClaimOwnership;
import me.ryanhamshire.GriefPrevention.Claim;
import me.ryanhamshire.GriefPrevention.ClaimPermission;
import me.ryanhamshire.GriefPrevention.CreateClaimResult;
import me.ryanhamshire.GriefPrevention.GriefPrevention;
import me.ryanhamshire.GriefPrevention.PlayerData;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Reserves the spawn square through GriefPrevention.
 *
 * <p>The only class in this plugin that names a GriefPrevention type. Everything the core
 * sees is {@link ProtectionProvider}, {@link ClaimResult} and {@link ClaimOutcome}, so a
 * server without GriefPrevention never loads any of the imports above, and
 * {@link ProtectionProviders} decides whether this class is constructed at all.
 *
 * <h2>What GriefPrevention's API does and does not check</h2>
 *
 * <p>Read off {@code DataStore.createClaim} rather than guessed from the method name,
 * because most of what a server owner thinks of as claiming lives in the command layer and
 * not in the API. From its own comment block: it <em>does</em> adjust the owner's claim
 * block balance on success and lets it go negative, it <em>does</em> reject an overlap and
 * hand back the conflicting claim, and it does <b>not</b> check that the player can afford
 * the claim, does <b>not</b> check the minimum claim size, and does <b>not</b> check any
 * permission. Its two entry points - {@code /claim} and the shovel handler - apply three
 * checks between them that the API does not, and this class ports them:
 *
 * <ul>
 *   <li><b>Claim blocks.</b> {@link ClaimOwnership#ADMIN_CLAIM} passes a {@code null}
 *       owner, which is how GriefPrevention spells an administrative claim: it belongs to
 *       the server, is charged to nobody, and the player is trusted onto it afterwards, so
 *       a server whose starting balance is zero still protects every spawn. Under
 *       {@link ClaimOwnership#PLAYER_CLAIM} the claim is the player's and its area is
 *       charged against their balance, so the balance is checked first and a player who
 *       cannot afford it gets {@link ClaimOutcome#REFUSED} and keeps a balance of zero
 *       rather than a debt. Refusing is an ordinary outcome here, not a fault, and it is
 *       not permanent either: GriefPrevention accrues claim blocks for time played, so the
 *       same player can succeed later.</li>
 *   <li><b>Claim count.</b> {@code MaxClaimsPerPlayer} is enforced by both entry points and
 *       by neither the API, so a server that has capped players at a number would otherwise
 *       find SpiralGenesis handing every one of them an extra claim, silently. Checked
 *       under {@code PLAYER_CLAIM} only, because an admin claim belongs to the server and
 *       counts against nobody's total.</li>
 *   <li><b>Minimum claim size.</b> GriefPrevention's {@code MinimumWidth} and
 *       {@code MinimumArea} are server settings, and its own code applies them only to
 *       non-admin claims - the shovel handler guards the check with
 *       {@code shovelMode != ShovelMode.Admin}, and the fields are commented "minimum width
 *       for non-admin claims". So both numbers are read once here, at construction,
 *       compared once against the configured size, and reported once as a startup warning
 *       naming both. A size below the minimum then answers
 *       {@link ClaimOutcome#BELOW_MINIMUM_SIZE} without calling GriefPrevention at all,
 *       because the answer cannot change until somebody edits a file. Nothing about it is
 *       per player, so nothing about it is logged per player.</li>
 * </ul>
 *
 * <p>One command-layer check is deliberately <em>not</em> ported: the shovel handler
 * requires {@code griefprevention.createclaims} of the player wielding it. The spawn claim
 * is created by the server rather than by the player, on a square the server chose, and the
 * owner is routinely offline - a backfill always is - where Bukkit offers no permission to
 * check. Gating on a node that can only be read for half the callers would make the feature
 * behave differently depending on whether the player happened to be online.
 *
 * <h2>Threading contract</h2>
 *
 * <p><b>{@link #reserve} and {@link #release} must be called on the thread that owns the
 * region containing the claim.</b> GriefPrevention does not run on Folia, so on every server
 * where this provider is available that thread is the server's main thread, and the check
 * below is {@link Bukkit#isPrimaryThread()}. {@code release} carries the contract for the
 * stronger reason: {@code deleteClaim} dispatches {@code ClaimDeletedEvent} and drops the
 * claim out of the live claim list other plugins are reading.
 *
 * <p>The reason is not GriefPrevention's own data structures - {@code createClaim},
 * {@code getPlayerData} and {@code saveClaim} are all {@code synchronized} on the one data
 * store, so the claim list and the player cache are guarded. It is everything the call
 * reaches through: {@code createClaim} dispatches {@code ClaimCreatedEvent} to every other
 * plugin's listeners, reads the world border, and this class asks Bukkit for the owner's
 * {@link Player}. None of those are safe off the main thread, and allocation in this plugin
 * runs its terrain search asynchronously, so a caller reserving from wherever that search
 * finished would be handing another plugin's listener a call it is entitled to assume is
 * synchronous.
 *
 * <p>A wrong-thread call is a programming error, so it is made loud rather than tolerated.
 * The first one is logged at {@code SEVERE} with a synthetic stack trace naming the
 * offending call site; every one after that is a one-line {@code SEVERE}, loud enough that
 * a developer cannot miss it and compact enough that it cannot fill a live server's log
 * with stack traces. Either way the claim is refused without GriefPrevention being touched.
 */
public final class GriefPreventionProtectionProvider implements ProtectionProvider {

    /**
     * GriefPrevention's own {@code plugin.yml} {@code name:}, verified against the shipped
     * jar. It has to match exactly here and in this plugin's {@code softdepend:}, or the
     * load-order guarantee silently does not apply and {@link GriefPrevention#instance} is
     * still null when this class resolves it.
     */
    public static final String PLUGIN_NAME = "GriefPrevention";

    private final Logger logger;
    private final ClaimOwnership ownership;
    private final int configuredSize;

    /**
     * Whether GriefPrevention answered at construction. No GriefPrevention object is held
     * past the constructor: the plugin is a singleton reachable through a static field, and
     * caching a reference to it would only outlive a reload.
     */
    private final boolean available;

    /**
     * GriefPrevention's minimum claim dimensions, read once at construction.
     *
     * <p>Once, because that is what makes the answer a startup line rather than a per-join
     * one. A GriefPrevention reload that changed them would not be seen until this plugin's
     * own {@code /sgen reload}, which rebuilds this object - an acceptable staleness for a
     * pair of numbers that exist to be set at install time.
     */
    private final int minWidth;
    private final int minArea;

    /** Whether the configured size is the one below the minimum. Decided and logged once. */
    private final boolean configuredSizeBelowMinimum;

    /** Worlds already reported as having claiming switched off, so each is said once. */
    private final Set<String> reportedDisabledWorlds = ConcurrentHashMap.newKeySet();

    /** Whether a wrong-thread call has already printed its stack trace. */
    private final AtomicBoolean wrongThreadReported = new AtomicBoolean();

    /**
     * Resolves GriefPrevention once, and settles the minimum-size question once.
     *
     * @param plugin    this plugin, for its logger only
     * @param ownership whether the claim is an admin claim or the player's own
     * @param size      the configured side of the square in blocks, odd and at least 3
     */
    public GriefPreventionProtectionProvider(Plugin plugin, ClaimOwnership ownership, int size) {
        this.logger = plugin.getLogger();
        this.ownership = ownership;
        this.configuredSize = size;

        boolean resolved = false;
        boolean tooSmall = false;
        int width = 0;
        int area = 0;
        try {
            GriefPrevention gp = GriefPrevention.instance;
            if (gp == null || gp.dataStore == null) {
                // Installed and enabled, but its own enable did not finish. Silence here is
                // the hardest failure in this feature to diagnose, so it gets a line: the
                // server owner sees a plugin present, protection on, and no claims.
                logger.warning("GriefPrevention is enabled but has not published its API, so no "
                        + "spawn claims will be created. This usually means its own startup "
                        + "failed - check the log above for its errors.");
            } else {
                resolved = true;
                width = gp.config_claims_minWidth;
                area = gp.config_claims_minArea;
                tooSmall = belowMinimum(size, ownership, width, area);
                if (tooSmall) {
                    // Once, here, naming both numbers. A server owner cannot act on "the
                    // claim failed"; they can act on "81 is below 100".
                    logger.warning("Spawn protection is configured for a " + size + "x" + size
                            + " claim, which GriefPrevention will not accept from a player on this "
                            + "server: " + minimumDetail(size, width, area) + " No spawn claim will "
                            + "be created until protection.size is raised, GriefPrevention's "
                            + "minimum is lowered, or protection.claim-as is set to ADMIN_CLAIM, "
                            + "which the minimum does not apply to.");
                }
            }
        } catch (Throwable t) {
            // An API that has moved throws Error, not Exception, and it throws it here
            // rather than at a call site precisely because this is where the plugin is
            // resolved. Unavailable is the safe answer: nothing is claimed, nothing breaks.
            resolved = false;
            logger.log(Level.WARNING, "GriefPrevention is installed but its API could not be "
                    + "bound, so no spawn claims will be created.", t);
        }
        this.available = resolved;
        this.minWidth = width;
        this.minArea = area;
        this.configuredSizeBelowMinimum = tooSmall;
    }

    /**
     * Matches the {@code GRIEF_PREVENTION} configuration value, so a log line and the file
     * agree.
     */
    @Override
    public String name() {
        return "GRIEF_PREVENTION";
    }

    @Override
    public boolean isAvailable() {
        return available;
    }

    /**
     * Whether the size this provider was configured with is below GriefPrevention's own
     * minimum, in which case it will never create a claim and has already said so.
     *
     * <p>Read by {@link ProtectionProviders} so that the startup line does not announce a
     * working feature one line after warning that it cannot work.
     */
    boolean isConfiguredSizeBelowMinimum() {
        return configuredSizeBelowMinimum;
    }

    @Override
    public ClaimResult reserve(Location centre, int requestedSize, UUID owner) {
        if (!available) {
            return ClaimResult.of(ClaimOutcome.PROVIDER_UNAVAILABLE);
        }
        if (owner == null || centre == null) {
            return ClaimResult.of(ClaimOutcome.REFUSED, "the claim request was incomplete.");
        }
        if (requestedSize < 3) {
            return ClaimResult.of(ClaimOutcome.REFUSED, "a claim of side " + requestedSize
                    + " is smaller than a single square around the spawn block.");
        }
        // Asked of the size in hand rather than of the configured one, so a caller passing
        // a size of its own gets an answer about that size. The configured size is what the
        // startup warning is about; this is what the claim would be.
        if (belowMinimum(requestedSize, ownership, minWidth, minArea)) {
            // Nothing here calls GriefPrevention: the answer is arithmetic on two numbers
            // read at startup, and it cannot change until somebody edits a file.
            return ClaimResult.of(ClaimOutcome.BELOW_MINIMUM_SIZE,
                    minimumDetail(requestedSize, minWidth, minArea));
        }
        if (!Bukkit.isPrimaryThread()) {
            reportWrongThread();
            return ClaimResult.of(ClaimOutcome.REFUSED,
                    "the claim was requested off the server thread and was not attempted.");
        }

        try {
            return createClaim(centre, requestedSize, owner);
        } catch (Throwable t) {
            // Throwable, not Exception, and wrapped around everything that touches either
            // GriefPrevention or the Location: a GriefPrevention whose API has moved throws
            // NoSuchMethodError or NoSuchFieldError, both Errors, and Location.getWorld
            // throws IllegalArgumentException once the world it names has been unloaded,
            // which the backfill path can reach with a stored spawn. A failed claim must
            // cost the player a claim, never a join.
            logger.log(Level.WARNING, "GriefPrevention threw while claiming the spawn square for "
                    + owner + ". The player keeps the spawn and loses the protection.", t);
            return ClaimResult.of(ClaimOutcome.REFUSED, "GriefPrevention threw: " + describe(t));
        }
    }

    /**
     * Deletes the spawn claim at {@code centre}, and only if it is recognisably the one this
     * class would have created there.
     *
     * <p>Every guard in {@link #matches} has to pass. That is not defensiveness for its own
     * sake: this is the only method in the plugin that can destroy a player's work, it is
     * reachable from an operator command, and the cost of getting it wrong is not symmetric
     * with the cost of declining. Declining leaves one stale square on a server; deleting the
     * wrong claim opens whatever a player built inside it to anyone who walks past. So the
     * answer is {@link ReleaseOutcome#NOT_OURS} at the first sign that what is there is not
     * what was put there, and the operator is told what was found instead.
     */
    @Override
    public ReleaseResult release(Location centre, int requestedSize, UUID owner) {
        if (!available) {
            return ReleaseResult.of(ReleaseOutcome.PROVIDER_UNAVAILABLE);
        }
        if (owner == null || centre == null || requestedSize < 3) {
            return ReleaseResult.of(ReleaseOutcome.REFUSED, "the release request was incomplete.");
        }
        if (!Bukkit.isPrimaryThread()) {
            reportWrongThread();
            return ReleaseResult.of(ReleaseOutcome.REFUSED,
                    "the release was requested off the server thread and was not attempted.");
        }

        try {
            return deleteClaim(centre, requestedSize, owner);
        } catch (Throwable t) {
            // Throwable for the same reasons reserve catches it, plus one of its own: a
            // release runs against a location read back out of data.yml, whose world may
            // have been unloaded since it was written.
            logger.log(Level.WARNING, "GriefPrevention threw while releasing the spawn claim for "
                    + owner + ". The claim is still standing.", t);
            return ReleaseResult.of(ReleaseOutcome.REFUSED, "GriefPrevention threw: " + describe(t));
        }
    }

    /** The deletion itself, factored out so {@link #release} holds one catch block. */
    private ReleaseResult deleteClaim(Location centre, int requestedSize, UUID owner) {
        if (!Bukkit.getPluginManager().isPluginEnabled(PLUGIN_NAME)) {
            return ReleaseResult.of(ReleaseOutcome.PROVIDER_UNAVAILABLE);
        }
        GriefPrevention gp = GriefPrevention.instance;
        if (gp == null || gp.dataStore == null) {
            return ReleaseResult.of(ReleaseOutcome.PROVIDER_UNAVAILABLE);
        }
        if (centre.getWorld() == null) {
            return ReleaseResult.of(ReleaseOutcome.REFUSED, "the release request named no world.");
        }

        // ignoreHeight, because the vertical extent of a claim depends on
        // ExtendIntoGroundDistance at the moment it was made and a server owner may have
        // changed it since. The horizontal square is what identifies the claim, and it is
        // checked exactly below rather than approximately here.
        Claim claim = gp.dataStore.getClaimAt(centre, true, null);
        if (claim == null) {
            return ReleaseResult.of(ReleaseOutcome.NOT_FOUND);
        }

        String mismatch = matches(claim, centre, requestedSize, owner);
        if (mismatch != null) {
            return ReleaseResult.of(ReleaseOutcome.NOT_OURS, mismatch);
        }

        Long id = claim.getID();
        gp.dataStore.deleteClaim(claim);
        return ReleaseResult.of(ReleaseOutcome.RELEASED, "claim " + id + " at "
                + centre.getBlockX() + "," + centre.getBlockZ() + " in "
                + centre.getWorld().getName());
    }

    /**
     * Whether {@code claim} is exactly the claim {@link #reserve} would have created for
     * these arguments.
     *
     * @return {@code null} when it matches and may be deleted, otherwise the wording that
     *         says what was found instead
     */
    private String matches(Claim claim, Location centre, int requestedSize, UUID owner) {
        if (claim.parent != null) {
            // A subdivision inside a larger claim. Never created here, and deleting it would
            // change a claim this plugin has nothing to do with.
            return "the claim at that point is a subdivision of a larger claim, which "
                    + "SpiralGenesis did not create.";
        }
        if (claim.children != null && !claim.children.isEmpty()) {
            // GriefPrevention deletes a parent's subdivisions along with it, so this would
            // destroy more than the square even if the square itself matched.
            return "the claim at that point has " + claim.children.size() + " subdivisions "
                    + "inside it, which deleting it would take with it.";
        }

        if (ownership == ClaimOwnership.ADMIN_CLAIM) {
            if (!claim.isAdminClaim()) {
                return "the claim at that point belongs to a player, and protection.claim-as "
                        + "is ADMIN_CLAIM, so SpiralGenesis did not create it.";
            }
            // An administrative claim has no owner to compare against - that is what makes it
            // administrative - so without this the identity test under the default setting
            // would be geometry alone, and a hand-made admin claim that happened to be
            // exactly this square on exactly this block would be deleted. Every admin claim
            // this class creates trusts its owner in the same breath (see trustOwner), so the
            // explicit permission is a signature the class writes itself and a hand-made
            // claim has no reason to carry.
            if (!claim.hasExplicitPermission(owner, ClaimPermission.Build)) {
                return "the administrative claim at that point does not trust its intended "
                        + "owner on it, and every one SpiralGenesis creates does, so it was "
                        + "made some other way.";
            }
        } else if (!owner.equals(claim.getOwnerID())) {
            return "the claim at that point belongs to somebody else.";
        }

        int radius = (requestedSize - 1) / 2;
        Location lesser = claim.getLesserBoundaryCorner();
        Location greater = claim.getGreaterBoundaryCorner();
        if (lesser == null || greater == null) {
            return "the claim at that point has no readable boundary.";
        }
        boolean sameSquare = lesser.getBlockX() == Math.subtractExact(centre.getBlockX(), radius)
                && greater.getBlockX() == Math.addExact(centre.getBlockX(), radius)
                && lesser.getBlockZ() == Math.subtractExact(centre.getBlockZ(), radius)
                && greater.getBlockZ() == Math.addExact(centre.getBlockZ(), radius);
        if (!sameSquare) {
            // The likeliest cause by far is the owner resizing their own spawn claim
            // outwards to take in what they have built, which is precisely the case where
            // deleting it would be worst.
            return "the claim at that point is " + claim.getWidth() + "x" + claim.getHeight()
                    + " and not the " + requestedSize + "x" + requestedSize + " square "
                    + "SpiralGenesis creates, so it has been resized or was made by hand.";
        }
        return null;
    }

    /**
     * The GriefPrevention call itself, factored out so {@link #reserve} holds one
     * {@code catch (Throwable)} rather than one per branch. Everything that can throw is
     * inside it, including reading the world out of {@code centre}.
     *
     * <p>Geometry and vertical extent follow GriefPrevention's own {@code /claim} command
     * verbatim: the claim reaches {@code ExtendIntoGroundDistance} blocks below the spawn
     * block, plus one, and up to the world's build ceiling. Matching the command matters
     * because a claim made any other way behaves differently the moment a player tries to
     * resize it with a shovel. The coordinate arithmetic uses the exact variants for the
     * same reason the command does: {@code protection.size} is clamped to 3-255 today, but
     * the arithmetic should not be the reason that has to stay true.
     */
    private ClaimResult createClaim(Location centre, int requestedSize, UUID owner) {
        // isPluginEnabled rather than a null check on the singleton: GriefPrevention's
        // onDisable clears neither instance nor dataStore, so a plugin that has been
        // unloaded still answers both, and claims written into that data store are never
        // saved. The plugin manager is the only thing that knows.
        if (!Bukkit.getPluginManager().isPluginEnabled(PLUGIN_NAME)) {
            return ClaimResult.of(ClaimOutcome.PROVIDER_UNAVAILABLE);
        }
        GriefPrevention gp = GriefPrevention.instance;
        if (gp == null || gp.dataStore == null) {
            return ClaimResult.of(ClaimOutcome.PROVIDER_UNAVAILABLE);
        }

        World world = centre.getWorld();
        if (world == null) {
            return ClaimResult.of(ClaimOutcome.REFUSED, "the claim request named no world.");
        }
        if (!gp.claimsEnabledForWorld(world)) {
            return ClaimResult.of(ClaimOutcome.REFUSED, disabledWorldDetail(world));
        }

        // Asked before anything is charged, and that ordering is the whole of the backfill's
        // idempotency under PLAYER_CLAIM. createClaim detects an overlap perfectly well by
        // itself, but only after this class has already run its claim-block pre-flight - so a
        // second backfill pass over a player whose balance the first pass spent would be
        // refused for insufficient claim blocks and counted as a failure, reporting work that
        // had in fact been done correctly as an alarming problem. Asking here answers
        // ALREADY_CLAIMED instead, for both ownership modes, and skips the balance read
        // entirely - which under PLAYER_CLAIM is a synchronous load of an offline player's
        // data, so this is also the cheaper order on the path that does thousands of them.
        //
        // Not a replacement for createClaim's own check: this only sees a claim covering the
        // centre block, and a claim clipping a corner of the square still has to be caught
        // where it always was.
        Claim covering = gp.dataStore.getClaimAt(centre, true, null);
        if (covering != null) {
            return ClaimResult.of(ClaimOutcome.ALREADY_CLAIMED, describeOverlap(covering));
        }

        int radius = (requestedSize - 1) / 2;
        int lesserX = Math.subtractExact(centre.getBlockX(), radius);
        int greaterX = Math.addExact(centre.getBlockX(), radius);
        int lesserZ = Math.subtractExact(centre.getBlockZ(), radius);
        int greaterZ = Math.addExact(centre.getBlockZ(), radius);
        int lesserY = Math.subtractExact(
                Math.subtractExact(centre.getBlockY(), gp.config_claims_claimsExtendIntoGroundDistance), 1);
        int greaterY = world.getMaxHeight();

        UUID claimOwner;
        if (ownership == ClaimOwnership.ADMIN_CLAIM) {
            // A null owner is how GriefPrevention spells an administrative claim; see
            // Claim.isAdminClaim, which is exactly this test. Charged to nobody, counted
            // against nobody's claim limit.
            claimOwner = null;
        } else {
            ClaimResult refusal = checkPlayerClaimAllowed(gp, owner, requestedSize);
            if (refusal != null) {
                return refusal;
            }
            claimOwner = owner;
        }

        // Null when the owner is offline, which backfilling a stored spawn always is. The
        // only things GriefPrevention uses it for are the WorldGuard build check and the
        // ClaimCreatedEvent's creator, and both accept its absence.
        Player creator = Bukkit.getPlayer(owner);

        CreateClaimResult result = gp.dataStore.createClaim(world,
                lesserX, greaterX,
                lesserY, greaterY,
                lesserZ, greaterZ,
                claimOwner, null, null, creator);

        if (result == null) {
            return ClaimResult.of(ClaimOutcome.REFUSED, "GriefPrevention returned no result.");
        }
        if (!result.succeeded || result.claim == null) {
            if (result.claim != null) {
                // GriefPrevention hands back the claim that got in the way. Traced through
                // every failure return in createClaim: an overlap is the only one that
                // carries a claim, and the world border, WorldGuard and cancelled-event
                // paths all leave it null.
                return ClaimResult.of(ClaimOutcome.ALREADY_CLAIMED, describeOverlap(result.claim));
            }
            return ClaimResult.of(ClaimOutcome.REFUSED, "GriefPrevention refused the claim - the "
                    + "square is outside the world border, inside a region the player cannot build "
                    + "in, or another plugin cancelled the creation.");
        }

        if (ownership == ClaimOwnership.ADMIN_CLAIM) {
            trustOwner(gp, result.claim, owner);
        }
        return ClaimResult.of(ClaimOutcome.CREATED, "claim " + result.claim.getID() + ", "
                + requestedSize + "x" + requestedSize + " at " + centre.getBlockX() + ","
                + centre.getBlockZ() + " in " + world.getName());
    }

    /**
     * The two checks GriefPrevention's command layer makes before creating a claim a player
     * will own, and its API makes for nobody.
     *
     * <p>Returns the refusal, or {@code null} when the player may have the claim - the one
     * place in this class where {@code null} means anything, and it never leaves it.
     *
     * <p>Note for the backfill: reading a balance for an offline player makes
     * GriefPrevention load that player's data from its storage, synchronously, on the
     * calling thread, and it stays in GriefPrevention's cache until they next log out.
     * Backfilling several hundred stored spawns in one tick under {@code PLAYER_CLAIM} is
     * therefore several hundred file or database reads on the main thread, which is why that
     * command spreads its work over ticks against a clock rather than a count alone.
     * {@code ADMIN_CLAIM}, the default, never reaches this method at all - and neither does
     * a spawn that is already claimed, since the overlap check in {@link #createClaim} now
     * runs before this one.
     */
    private ClaimResult checkPlayerClaimAllowed(GriefPrevention gp, UUID owner, int requestedSize) {
        PlayerData data = gp.dataStore.getPlayerData(owner);
        if (data == null) {
            return ClaimResult.of(ClaimOutcome.REFUSED,
                    "GriefPrevention has no claim block record for the player.");
        }

        int maxClaims = gp.config_claims_maxClaimsPerPlayer;
        int held = data.getClaims().size();
        if (maxClaims > 0 && held >= maxClaims) {
            return ClaimResult.of(ClaimOutcome.REFUSED, "the player already holds " + held
                    + " claims and GriefPrevention's MaxClaimsPerPlayer is " + maxClaims
                    + ". Set protection.claim-as to ADMIN_CLAIM, which counts against nobody's "
                    + "limit, or raise the limit.");
        }

        long area = (long) requestedSize * requestedSize;
        int remaining = data.getRemainingClaimBlocks();
        if (remaining < area) {
            // createClaim would happily take them negative, so the check is here and not
            // there. Ordinary on a server that starts players at zero, which is why it is
            // REFUSED with a reason rather than an exception.
            return ClaimResult.of(ClaimOutcome.REFUSED, insufficientBlocksDetail(remaining, area));
        }
        return null;
    }

    /**
     * Trusts the player onto an administrative claim, which is the whole point of creating
     * one: without this the claim protects the spawn from its own occupant as thoroughly as
     * from anyone else.
     *
     * <p>{@code Build} rather than {@code Access} or {@code Inventory}, because
     * GriefPrevention's own hierarchy has {@code Build} grant both of those - the bed, the
     * first chest and the ground are all covered by the one level. The identifier is the
     * UUID's string form, which is exactly what {@code /trust} stores for a known player,
     * and {@code setPermission} followed by {@code saveClaim} is exactly what it does with
     * it. What {@code /trust} additionally does and this does not is fire
     * {@code TrustChangedEvent} and honour a cancellation: the event reports a player
     * granting trust on a claim they hold, and here the server is trusting a player onto a
     * claim the server just made for them, which is not the same act.
     *
     * <p>A failure here is not a failed claim: the square is already reserved and the
     * outcome is still {@link ClaimOutcome#CREATED}. It is worth a warning, though, because
     * a claim the player cannot build in is worse for them than no claim at all.
     */
    private void trustOwner(GriefPrevention gp, Claim claim, UUID owner) {
        try {
            claim.setPermission(owner.toString(), ClaimPermission.Build);
            gp.dataStore.saveClaim(claim);
        } catch (Throwable t) {
            logger.log(Level.WARNING, "The spawn claim for " + owner + " was created as an admin "
                    + "claim, but they could not be trusted onto it, so they cannot build on their "
                    + "own spawn. Trust them manually, or set protection.claim-as to PLAYER_CLAIM.",
                    t);
        }
    }

    /**
     * Says once per world that GriefPrevention is not claiming there, and returns the
     * detail either way.
     *
     * <p>Once per world because the condition is a server setting: it is permanent, it is
     * not the joining player's doing, and every player allocated into that world would
     * otherwise produce the same line. The same reasoning as the minimum-size warning, one
     * level down, because the world is not known until a claim is asked for.
     */
    private String disabledWorldDetail(World world) {
        String name = world.getName();
        if (reportedDisabledWorlds.add(name)) {
            logger.warning("GriefPrevention has claiming switched off in world '" + name
                    + "', so no spawn claims will be created there. This is GriefPrevention's own "
                    + "per-world setting; nothing in SpiralGenesis can override it.");
        }
        return "GriefPrevention has claiming switched off in world '" + name + "'.";
    }

    /**
     * Reports a call from the wrong thread: a stack trace the first time, a one-liner every
     * time after. See the threading contract in the class notes for why this is loud rather
     * than tolerated, and why it does not stay loud enough to bury a live server's log.
     */
    private void reportWrongThread() {
        String thread = Thread.currentThread().getName();
        String message = "SpiralGenesis called the GriefPrevention protection provider from "
                + thread + ", which is not the server thread. The claim was not created. This is "
                + "a bug in the calling code, not a server misconfiguration.";
        if (wrongThreadReported.compareAndSet(false, true)) {
            logger.log(Level.SEVERE, message + " The stack trace below names the call site; "
                    + "further occurrences are logged without one.",
                    new IllegalStateException("ProtectionProvider.reserve off the main thread"));
        } else {
            logger.severe(message);
        }
    }

    // ---------------------------------------------------------------------------------
    // Decision helpers. Package-private and free of GriefPrevention types on purpose: they
    // carry the arithmetic worth testing, and a test should not need a server to reach it.
    // ---------------------------------------------------------------------------------

    /**
     * Whether GriefPrevention would reject this size as too small.
     *
     * <p>Both of its settings have to be satisfied: a square passes only if its side is at
     * least {@code minWidth} and its area at least {@code minArea}. A default install is a
     * width of 5 and an area of 100, so the default 9x9 spawn square clears the width and
     * misses the area by 19 blocks. That is the miss this whole path exists for, and it is
     * the default configuration rather than an unusual one.
     *
     * <p>Always false for an administrative claim: GriefPrevention applies neither setting
     * to one, which its own fields say in as many words - "minimum width for non-admin
     * claims".
     */
    static boolean belowMinimum(int size, ClaimOwnership ownership, int minWidth, int minArea) {
        if (ownership == ClaimOwnership.ADMIN_CLAIM) {
            return false;
        }
        return size < minWidth || (long) size * size < minArea;
    }

    /** The half of the startup warning that names GriefPrevention's numbers. */
    static String minimumDetail(int size, int minWidth, int minArea) {
        StringBuilder detail = new StringBuilder();
        if (size < minWidth) {
            detail.append("GriefPrevention's MinimumWidth is ").append(minWidth)
                    .append(" and the configured side is ").append(size).append('.');
        }
        long area = (long) size * size;
        if (area < minArea) {
            if (detail.length() > 0) {
                detail.append(' ');
            }
            detail.append("GriefPrevention's MinimumArea is ").append(minArea).append(" and a ")
                    .append(size).append('x').append(size).append(" square is ").append(area)
                    .append(" blocks.");
        }
        return detail.toString();
    }

    /** The wording for a player who cannot afford their own spawn claim. */
    static String insufficientBlocksDetail(int remaining, long area) {
        return "the player holds " + remaining + " claim blocks and the claim needs " + area
                + ". Give new players a starting balance, shrink protection.size, or set "
                + "protection.claim-as to ADMIN_CLAIM, which costs them nothing.";
    }

    /** The wording for an overlap, naming who is already there so an admin can go and look. */
    private static String describeOverlap(Claim existing) {
        String ownerName;
        try {
            ownerName = existing.isAdminClaim() ? "an administrative claim" : existing.getOwnerName();
        } catch (Throwable t) {
            ownerName = "an existing claim";
        }
        return "the square overlaps claim " + existing.getID() + " (" + ownerName + ").";
    }

    /** A one-line rendering of a throwable, for a detail string that must not be a stack trace. */
    private static String describe(Throwable t) {
        String message = t.getMessage();
        return t.getClass().getSimpleName() + ((message == null) ? "" : ": " + message);
    }

    @Override
    public String toString() {
        return "GriefPreventionProtectionProvider[" + ownership.name().toLowerCase(Locale.ROOT)
                + ", " + configuredSize + "x" + configuredSize + ", available=" + available + "]";
    }
}
