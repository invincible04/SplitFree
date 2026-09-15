# README screenshots

Real Compose UI rendered with sample data by Robolectric native-graphics tests.

[Project overview](../../README.md) · [Contributor guide](../../CONTRIBUTING.md#testing)

## Provenance

| Property | Value |
| --- | --- |
| Source revision | `52187cb` |
| Generated | September 14, 2026 |
| Capture method | Native-graphics Compose test fixtures. |
| Image edits | Lossless PNG optimization only; decoded pixels match the original test outputs. |
| Data | Test fixtures, not real user records. |
| Resolution | 390 × 844 pixels, displayed at 195 pixels wide in the root README (2× density). |

- These images illustrate the UI.
- They are **not device screenshots** or evidence of radio, background-delivery, or Keystore behavior.

## Image map

Generated paths below are relative to `app/build/outputs/`.

| Public image | Test class | Generated image |
| --- | --- | --- |
| [groups.png](groups.png) | `GroupsListContentTest` | `ui-screenshots/home-light.png` |
| [add-expense.png](add-expense.png) | `AddExpenseContentTest` | `expense-screenshots/expense-render-light.png` |
| [balances-dark.png](balances-dark.png) | `GroupDetailContentTest` | `ui-screenshots/group-summary-dark.png` |

## Regenerate

Run from the repository root with the [documented toolchain](../../README.md#build-from-source):

```bash
./gradlew :app:testDebugUnitTest --rerun-tasks \
  --tests 'com.splitfree.ui.screens.group.GroupsListContentTest' \
  --tests 'com.splitfree.ui.screens.expense.AddExpenseContentTest' \
  --tests 'com.splitfree.ui.screens.groupdetail.GroupDetailContentTest'
```

1. Confirm the selected test suites pass.
2. Copy the three outputs to their public filenames above.
3. Apply lossless PNG compression without resizing or color quantization; verify decoded RGBA pixels still match the
   test outputs.
4. Update the source revision and generation date in this guide.
5. Check the root README layout, image paths, and alt text.

## Review before committing

- Show only sample groups, identities, and expenses.
- Never capture real recovery phrases, private keys, or usable invitations.
- Inspect text readability, clipping, and light/dark colors.
- Keep fixture renders labelled as such; do not describe them as device acceptance.
