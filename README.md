# WorldEditSlimefun

WorldEditSlimefun is a Slimefun-aware editing and recovery addon maintained for modern Paper servers.

This fork targets **Paper 26.2**, works with **FastAsyncWorldEdit (FAWE)** through the normal WorldEdit API, and keeps the original WorldEditSlimefun functionality for mass-pasting and testing Slimefun blocks.

## Requirements

- Paper 26.2+
- Java 25 runtime
- Slimefun / Slimefun Legacy
- WorldEdit-compatible provider
  - FastAsyncWorldEdit 2.15.3+ is recommended for Paper 26.2

FAWE provides the WorldEdit API, so you should not install standard WorldEdit alongside FAWE.

## Selection

The addon now uses your active **WorldEdit/FAWE selection** first.

Use the normal FAWE tools such as `//wand`, `//pos1`, and `//pos2`, then run the `/wesf` commands.

The original WESF selection wand and `/wesf pos1` / `/wesf pos2` remain available as a fallback.

## Recovery of an area cleared with FAWE

If an area was cleared with FAWE, recovery has two separate parts:

1. **Restore the normal world blocks with FAWE history** when the old history still exists.
2. **Repair surviving Slimefun block records** with WorldEditSlimefun.

FAWE stores edit history on disk unless history was bypassed or removed. Its history tools can search and roll back previous edits. If the old edit is still present in FAWE history, restore that edit first.

After the terrain/build has been restored, select the damaged area with FAWE and run:

```text
/wesf audit
```

This reports Slimefun database records whose physical blocks are missing or no longer match the expected Slimefun block material.

Then run:

```text
/wesf recover
```

This conservatively restores missing physical Slimefun blocks only where a valid Slimefun record still exists.

If a non-air block currently occupies a saved Slimefun position and you intentionally want the Slimefun block restored over it, use:

```text
/wesf recover true
```

Use the force option carefully.

### Important recovery limitation

WorldEditSlimefun cannot reconstruct Slimefun metadata that was already deleted from Slimefun's storage database. The recovery command intentionally does **not** invent missing IDs, inventories, energy values, or machine state.

If the Slimefun record survived the old FAWE edit, the addon can restore the physical block while preserving that saved record. If the record itself is gone, recovery requires an older Slimefun/database backup.

A normal server restart is recommended after restoring Slimefun blocks so tickers and menus can initialize cleanly.

## Commands

- `/wesf wand`
  - Gives the legacy WESF selection wand.
- `/wesf pos1`
  - Sets legacy WESF position 1.
- `/wesf pos2`
  - Sets legacy WESF position 2.
- `/wesf audit`
  - Audits the active FAWE/WorldEdit selection for surviving Slimefun records and material mismatches.
- `/wesf recover [force]`
  - Restores physical Slimefun block materials from surviving Slimefun records.
  - `force` defaults to `false`.
- `/wesf paste <slimefun_block> [flags...]`
  - Fills the selected area with the specified Slimefun block.
- `/wesf clear [call_event]`
  - Clears the selected area and removes associated Slimefun block data.

### Paste flags

- `--energy true|false`
- `--inputs [ITEMS...]`
- `--refill_inputs_task true|false`
- `--void_outputs_task true|false`
- `--task_timeout 30s|5m|1h`

## Safety

Slimefun database operations are not FAWE bulk block operations. To avoid accidentally locking the server with a massive synchronous scan, the addon has a selection safety limit:

```yaml
max-selection-blocks: 2000000
```

Set it to `0` to disable the limit, or raise it carefully for a known recovery region.

## Build artifact

GitHub Actions builds and exposes the release JAR as a raw artifact:

```text
SF_SFLWorldEdit_1.0.1.jar
```

The same raw JAR is published as a GitHub Release asset from the default branch/tag release workflow.

## Credits

Original WorldEditSlimefun project by **J3fftw1** and contributors in the Slimefun Addon Community.

This fork preserves that work while updating compatibility and adding server-owner recovery tooling for modern Paper/FAWE environments.
