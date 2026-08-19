# WorldEditSlimefun

WorldEditSlimefun is a Slimefun-aware editing, schematic backup, and recovery addon maintained for modern Paper servers.

This fork targets **Paper 26.2**, works with **FastAsyncWorldEdit (FAWE)** through the normal WorldEdit API, and keeps the original WorldEditSlimefun mass-paste/testing tools.

## Requirements

- Paper 26.2+
- Java 25 runtime
- Slimefun / Slimefun Legacy
- WorldEdit-compatible provider
  - FastAsyncWorldEdit 2.15.3+ is recommended for Paper 26.2

FAWE provides the WorldEdit API, so you should not install standard WorldEdit alongside FAWE.

## Slimefun-aware schematic backups

Version 1.0.2 adds an integrated backup/restore path for builds containing Slimefun machines, cargo, storage, Networks blocks, androids, energy components, and other Slimefun blocks.

Select the area with WorldEdit/FAWE, then save it with:

```text
/wesf schem save NAME
```

WESF writes two files to the normal WorldEdit/FAWE schematic folder:

- `NAME.schem` — the normal Sponge schematic containing blocks, block-entity NBT/PDC, and entities.
- `NAME.wesf.yml` — WESF recovery data containing Slimefun IDs, per-block key/value data, and non-preset Slimefun menu inventory contents.

To overwrite an existing backup intentionally:

```text
/wesf schem save NAME true
```

To restore it:

```text
/wesf schem load NAME
/wesf paste
```

`/wesf paste` pastes the FAWE/WorldEdit clipboard and then rebuilds the saved Slimefun records at the pasted positions. It also supports the active clipboard transform, so normal WorldEdit rotations/flips are respected when mapping saved Slimefun blocks.

For the most exact recovery of cargo links, Networks layouts, and other data that may contain absolute coordinates, restore the schematic at its original location/origin when possible.

### Old schematics without a WESF sidecar

Old `.schem` files can still be loaded:

```text
/wesf schem load OLD_BACKUP
/wesf paste
```

If no `.wesf.yml` sidecar exists, WESF enters **legacy embedded-PDC relink mode**. After the physical schematic is pasted, it scans pasted block entities for embedded Slimefun IDs such as `slimefun:slimefun_block` and attempts to register those machines again.

This can recover working machine identities when the old schematic preserved that metadata, but it cannot invent external Slimefun database values that were never stored in the schematic. New backups made with `/wesf schem save` are therefore much more complete.

## Selection

The addon uses your active **WorldEdit/FAWE selection** first.

Use normal FAWE tools such as `//wand`, `//pos1`, and `//pos2`, then run the `/wesf` commands. The original WESF wand and `/wesf pos1` / `/wesf pos2` remain as a fallback.

## Recovery of an area cleared with FAWE

If an area was cleared with FAWE and no WESF schematic backup exists, recovery still has two parts:

1. Restore normal world blocks with FAWE history when the old history still exists.
2. Repair surviving Slimefun block records with WorldEditSlimefun.

After restoring the physical build, select the affected area and run:

```text
/wesf audit
/wesf recover
```

`/wesf audit` reports surviving Slimefun records whose physical blocks are missing or mismatched. `/wesf recover` conservatively recreates missing physical Slimefun blocks only where a valid Slimefun record still exists.

To intentionally replace a non-air mismatched block:

```text
/wesf recover true
```

Use the force option carefully.

## Commands

- `/wesf schem save <name> [overwrite]` — saves a normal schematic plus Slimefun recovery sidecar.
- `/wesf schem load <name>` — loads the schematic and its WESF sidecar when present.
- `/wesf schem list` — lists schematics in the WorldEdit/FAWE schematic folder.
- `/wesf paste` — pastes the loaded schematic and restores Slimefun data.
- `/wesf paste <slimefun_block> [flags...]` — legacy selection fill with one Slimefun block type.
- `/wesf audit` — audits the active selection for surviving Slimefun records and material mismatches.
- `/wesf recover [force]` — restores physical blocks from surviving Slimefun records.
- `/wesf clear [call_event]` — clears the selected area and removes associated Slimefun block data.
- `/wesf wand`, `/wesf pos1`, `/wesf pos2` — legacy WESF selection tools.

### Legacy paste flags

- `--energy true|false`
- `--inputs [ITEMS...]`
- `--refill_inputs_task true|false`
- `--void_outputs_task true|false`
- `--task_timeout 30s|5m|1h`

## Safety

Slimefun database operations are not FAWE bulk operations. The addon limits synchronous Slimefun scans by default:

```yaml
max-selection-blocks: 2000000
```

Set it to `0` to disable the limit, or raise it carefully for a known administrative recovery region.

A normal server restart is recommended after a large restore so Slimefun tickers, menus, Networks, and cargo systems reload cleanly.

## Build artifact

GitHub Actions exposes the compiled JAR as a **raw, uncompressed artifact**:

```text
SF_SFLWorldEdit_1.0.2.jar
```

The same raw JAR is published as a GitHub Release asset from the default branch/tag release workflow.

## Credits

Original WorldEditSlimefun project by **J3fftw1** and contributors in the Slimefun Addon Community.

This fork preserves that work while updating compatibility and adding server-owner backup/recovery tooling for modern Paper/FAWE environments.
