# Router artifact redistribution checklist

This is a blocking evidence checklist, not legal advice. An accountable human must review
the current terms for the exact base checkpoint and proposed distribution path.

- [ ] Record the exact FunctionGemma checkpoint revision and source hash.
- [ ] Record who accepted the applicable Gemma terms and when.
- [ ] Archive/hash the exact agreement version reviewed.
- [ ] Review the incorporated Gemma Prohibited Use Policy for the proposed feature.
- [ ] Obtain a written `APPROVED` or `NOT APPROVED` redistribution decision from the
      accountable reviewer; absence of a decision means blocked.
- [ ] If approved, provide downstream users the required use restrictions and agreement.
- [ ] Mark every modified model file as modified where the terms require it.
- [ ] Include and hash the required Notice file for non-hosted distribution.
- [ ] Confirm no additional terms conflict with the Gemma terms.
- [ ] Record the tuned and converted artifact SHA-256, byte size, model card, source,
      grammar compatibility, and rollback metadata.
- [ ] Sign the immutable model manifest with the pinned release key.
- [ ] Independently redownload and verify the artifact, manifest signature, and checksum.

If redistribution is not approved, do not publish the tuned artifact or bypass gated
access. Keep the deterministic router active.
