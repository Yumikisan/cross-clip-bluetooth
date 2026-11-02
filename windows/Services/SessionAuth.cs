using System;
using System.Security.Cryptography;
using System.Text;

namespace ClipSyncWindows.Services
{
    /// <summary>
    /// Short authentication string (SAS), the same idea Bluetooth itself uses for numeric
    /// comparison during pairing — applied at the application layer so the user can tell
    /// "this is really my phone" from "some other device grabbed the link".
    ///
    /// Both ends generate 16 random bytes and send them to each other. The code is
    /// HMAC-SHA256 over a label, keyed by both nonces in sorted order, so:
    ///   * both sides compute the same code without ever exchanging the code itself;
    ///   * anybody in the middle who substituted a nonce makes each leg compute a different
    ///     code, and the two screens no longer match.
    /// </summary>
    public static class SasCode
    {
        public const string Label = "ClipSync-SAS-v1";

        /// <summary>16 random bytes as 32 lowercase hex characters.</summary>
        public static string NewNonce() =>
            Convert.ToHexString(RandomNumberGenerator.GetBytes(16)).ToLowerInvariant();

        public static string Compute(string nonceA, string nonceB)
        {
            // Sorting makes the derivation symmetric: neither side is "first".
            string hi, lo;
            if (string.CompareOrdinal(nonceA, nonceB) <= 0) { hi = nonceA; lo = nonceB; }
            else { hi = nonceB; lo = nonceA; }

            using var hmac = new HMACSHA256(Encoding.UTF8.GetBytes(hi + lo));
            var mac = hmac.ComputeHash(Encoding.UTF8.GetBytes(Label));

            long value = ((long)mac[0] << 24) | ((long)mac[1] << 16) | ((long)mac[2] << 8) | mac[3];
            return (value % 1_000_000).ToString("D6");
        }
    }

    public enum AuthOutcome
    {
        /// <summary>Nothing to do yet.</summary>
        None,

        /// <summary>The user has to compare / confirm the code.</summary>
        NeedUser,

        /// <summary>Both ends confirmed (or neither required it).</summary>
        Authorized,

        /// <summary>Refused or timed out; the caller must close the link.</summary>
        Rejected
    }

    /// <summary>
    /// Per-connection authentication state. Every RFCOMM link gets a fresh instance, so a
    /// stale confirmation can never be replayed onto a new connection.
    /// </summary>
    public sealed class SessionAuth
    {
        /// <summary>How long the user has to confirm on both ends.</summary>
        public const int ConfirmTimeoutSeconds = 60;

        /// <summary>Grace period for peers that never speak the v3 auth frames.</summary>
        public const int LegacyGraceSeconds = 8;

        private readonly object _gate = new();

        public SessionAuth(bool requireConfirm)
        {
            RequireConfirm = requireConfirm;
            Sid = Guid.NewGuid().ToString("n").Substring(0, 12);
            LocalNonce = SasCode.NewNonce();
            CreatedAt = DateTime.UtcNow;
        }

        public string Sid { get; }
        public string LocalNonce { get; }
        public DateTime CreatedAt { get; }
        public bool RequireConfirm { get; }

        public string? PeerName { get; private set; }
        public string? PeerNonce { get; private set; }
        public string? Code { get; private set; }
        public DateTime? PromptedAt { get; private set; }

        public bool HeardPeer { get; private set; }
        public bool PeerNeedsConfirm { get; private set; }
        public bool MyConfirmed { get; private set; }
        public bool PeerConfirmed { get; private set; }
        public bool IsAuthenticated { get; private set; }
        public string? RejectReason { get; private set; }

        public bool IsRejected
        {
            get { lock (_gate) return RejectReason != null; }
        }

        public bool IsAuthenticatedSafe
        {
            get { lock (_gate) return IsAuthenticated; }
        }

        /// <summary>True when this side OR the peer asked for a confirmation code.</summary>
        public bool NeedsUserConfirm
        {
            get { lock (_gate) return RequireConfirm || PeerNeedsConfirm; }
        }

        public AuthOutcome NotePeerHello(string? name, bool needsConfirm)
        {
            lock (_gate)
            {
                if (IsAuthenticated || RejectReason != null) return AuthOutcome.None;
                if (!string.IsNullOrWhiteSpace(name)) PeerName = name;
                PeerNeedsConfirm = needsConfirm;
                HeardPeer = true;
                return Resolve();
            }
        }

        public AuthOutcome NotePeerNonce(string nonce)
        {
            lock (_gate)
            {
                if (IsAuthenticated || RejectReason != null) return AuthOutcome.None;
                if (nonce.Length != 32) return AuthOutcome.None;   // malformed → ignore, timeout will fire
                PeerNonce = nonce;
                return Resolve();
            }
        }

        /// <summary>The local user pressed Confirm / Reject.</summary>
        public AuthOutcome MarkMine(bool accept)
        {
            lock (_gate)
            {
                if (IsAuthenticated || RejectReason != null) return AuthOutcome.None;
                if (!accept)
                {
                    RejectReason = "local-rejected";
                    return AuthOutcome.Rejected;
                }
                MyConfirmed = true;
                return Resolve();
            }
        }

        /// <summary>The peer told us *its* user pressed Confirm / Reject.</summary>
        public AuthOutcome MarkPeer(bool accept)
        {
            lock (_gate)
            {
                if (IsAuthenticated || RejectReason != null) return AuthOutcome.None;
                if (!accept)
                {
                    RejectReason = "peer-rejected";
                    return AuthOutcome.Rejected;
                }
                PeerConfirmed = true;
                return Resolve();
            }
        }

        /// <summary>Called periodically so a link cannot sit half-authenticated forever.</summary>
        public AuthOutcome CheckTimeout(DateTime now)
        {
            lock (_gate)
            {
                if (IsAuthenticated || RejectReason != null) return AuthOutcome.None;

                var age = (now - CreatedAt).TotalSeconds;

                if (!HeardPeer && age > LegacyGraceSeconds)
                {
                    if (!RequireConfirm)
                    {
                        // Peer predates this feature and the user did not ask for a code:
                        // behave exactly like the previous release.
                        IsAuthenticated = true;
                        return AuthOutcome.Authorized;
                    }
                    RejectReason = "peer-unsupported";
                    return AuthOutcome.Rejected;
                }

                if (age > ConfirmTimeoutSeconds && !IsAuthenticated)
                {
                    RejectReason = PromptedAt != null ? "user-timeout" : "handshake-timeout";
                    return AuthOutcome.Rejected;
                }

                return AuthOutcome.None;
            }
        }

        // Must be called while holding _gate.
        private AuthOutcome Resolve()
        {
            if (IsAuthenticated) return AuthOutcome.None;
            if (RejectReason != null) return AuthOutcome.Rejected;
            if (!HeardPeer || PeerNonce == null) return AuthOutcome.None;

            Code ??= SasCode.Compute(LocalNonce, PeerNonce);

            var needsCode = RequireConfirm || PeerNeedsConfirm;
            if (!needsCode)
            {
                // Nobody asked for a code on either end: trust the link, same as before.
                MyConfirmed = true;
                PeerConfirmed = true;
                IsAuthenticated = true;
                return AuthOutcome.Authorized;
            }

            if (MyConfirmed && PeerConfirmed)
            {
                IsAuthenticated = true;
                return AuthOutcome.Authorized;
            }

            if (!MyConfirmed && PromptedAt == null)
            {
                PromptedAt = DateTime.UtcNow;
                return AuthOutcome.NeedUser;
            }

            return AuthOutcome.None;
        }
    }
}
