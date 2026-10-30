[Overview](../../README.md) · [Developer roadmap](../../docs/PIPELINE.md)

# Priperi2.12.1 single-approval fix

Specifically approved and applied once on29October2026. Existing project YOUR_PROJECT_REF now has001–016 installed. DO NOT replay202610280016_conversation_approval.sql or earlier migrations. No Edge update, billing, secret, table or grant change. The user-requested conversation consent rule is shared by both participants.

Two hash-guarded SQL functions change: contact_state reports a shared accepted conversation when either existing direction has explicit approval; the send cap checks that same pair decision. The approve action still requires a real incoming request from the target unless the conversation is already accepted (idempotent retry). An initiating sender cannot approve an unanswered request. Existing one-way approvals immediately count for both; counters/rows/accounts/messages are not rewritten or deleted. Blocks, live JWT/session/device proof, signatures, message expiry, queue limits and rate quotas still apply. Unapproved pairs retain the three-message cap in each direction.

Before request/helper prosrc MD5:a45eb11714e25023871086d68a8c72ea /2b944dca024e0b5dd9ff83cd27222124.
After:583c5f5bdb5a248d8e00f383aa63e5f5 /37c5340f9503ce623621bcb6d83cf2a0.

91backend tests and10final focused Android app tests pass. Tests prove both parties send beyond3afteroneapproval, initiating/third-party rejection, existing approval preservation without data/counter mutation, blocks and private grants. The final queued-outbox case and normal APK boundary checks pass. Client2.12.1/code40 also explains shared approval and wakes waiting messages when the approved state arrives. Older clients benefit from the server fix after their next sync.

Specific activation approval was received because this changes the consent boundary from directional permission to the user-requested shared conversation consent. The migration does not automatically accept previously unapproved pairs.

Live read-only postflight confirms both final routine hashes above, existing approvals open both ways, unapproved pairs restricted,0client table/RPC grants,0tables without RLS and no service_role access to the private helper. Before/after:3profiles,3messages,3permissions with1explicit approval. No owner account login or mutation was performed.
