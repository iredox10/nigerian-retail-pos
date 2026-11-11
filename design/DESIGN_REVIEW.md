# Design review — version 1.0 to 1.1

Date: 11 November 2026

Method: document/interaction review against the user's first-glance requirement, the complete feature scope, and the official sources in the design guide. This is an expert critique and proposed revision, not a usability-study result or a visual audit of a running app.

## Main finding

Version 1.0 protected offline records and had a coherent visual foundation, but treated the merchant too much like an experienced enterprise-software operator. It specified the full system more clearly than the first sale.

The revision keeps the light surfaces, ink typography, teal accent, unit-aware sales, and local-first financial model. It changes hierarchy, wording, default sizes, and everyday journeys. Full product scope is retained.

## Changes made

| Problem in 1.0 | Change in 1.1 | Intended benefit / evidence still needed |
|---|---|---|
| Navigation inventory exposed many sections and could become a long sidebar | Five everyday destinations plus More; advanced work is contextual | User can recognize a small set of daily jobs; validate findability of less frequent work |
| Sidebar could automatically become icons | Keep labels; compact screens use a labeled Menu | Does not depend on memorizing symbols |
| Compact controls and 12 sp status text were permitted | 44-unit default controls, 52-unit rows, 14 sp minimum operational labels, 40 sp total | Clearer reading and easier pointing; test on actual shop displays |
| Product and global search could compete | One dominant product search; global commands in utility/menu | Reduce wrong-context search |
| Prompt used SKU/barcode terminology and generic basket heading | Search for an item; Choose items; This sale | Recognition through the current trading task |
| Missing photos reduced items to indistinguishable package icons | Names, size, unit price, explicit add control; photos optional | Fully usable without expensive catalogue photography |
| Unit selection was a principle without a complete correction rule | Show chooser/default policy; preview quantity and price on conversion | Prevent bottle/carton mistakes |
| Payment flow could become popup-driven | Stable right-hand payment region with Back to items | Preserve context and confidence |
| Internal labels such as remaining to allocate or unverified dominated | Still to pay; Transfer not confirmed; named human actor in detail | Explain financial meaning before technical detail |
| A short payment lacked an explicit next-decision contract | Add another payment or Customer will pay later; no automatic debt | Protect customer/account balances |
| Change could disappear after completion | Keep Change to give visible on the saved receipt | Reduce memory burden during handover |
| Technical queue status filled persistent chrome | Plain save/connection state; diagnostics behind Details | Keep reassurance without implying bank confirmation or backup |
| Customer/supplier/cash accounting could leak into every screen | Plain daily views plus full authorized ledger detail | Preserve professional capability without mandatory jargon |
| Desired aesthetics were described more than composed | Receipt panel, fixed total, one teal action, hierarchy and spacing lock | Give implementers an original, repeatable visual target |
| No explicit test for first-glance understanding | Separate uncoached orientation and task protocol | Replace claims of intuitiveness with observed evidence |
| Long guide could be selectively followed | One-page brief, canonical copy file, stronger contributor instructions | Give every implementation the same initial hierarchy |

## Deliberately removed from the default experience

- Full feature tree in the main sidebar.
- Icon-only compact navigation.
- Expert reporting density applied to checkout.
- Queue counts, keyboard command bars, and technical transaction words as primary guidance.
- Generic confirmation after an already explicit normal cash completion.
- Mandatory receipt-choice dialog, forced customer registration, and full onboarding tours.
- Unexplained empty accounting rows and decorative charts on the sale screen.

These are presentation removals. Inventory, accounting, settlements, advanced modules, permissions, and diagnostic capabilities remain in scope.

## Added behavioral precision

- A first-time user can discover the normal path without a scanner, keyboard shortcuts, photos, or an instructor.
- Default unit selection is merchant-defined or barcode-specific, never inferred from the previous customer.
- Draft edits can be undone; completed business events require recorded corrections.
- Money received, debt, unconfirmed electronic amounts, change owed, sync, and backup remain distinct.
- Reassuring status copy cannot override a local-save failure, expired authorization, or missing required local data.
- Minimal setup does not invent zero costs or stock quantities to create attractive reports.

## Remaining uncertainties

1. Which words users prefer for Stock, Pay later, Put sale aside, and Money. Keep proposed labels until tested; avoid arbitrary synonyms per screen.
2. Grid versus list preference by trade and catalogue size. Both are supported with a stable explicit preference.
3. Whether the 380-unit payment pane needs a wider layout on target machines, especially with text scaling and longer translations.
4. Actual printer, scanner, keyboard, screen-reader, and Windows scaling behavior.
5. Whether real merchants understand the difference between locally recorded and bank-confirmed transfer payments without coaching.
6. Whether first-use setup can reach useful selling without producing incomplete or misleading stock/profit records.

Resolve these with the prototype and [merchant test plan](USABILITY_TEST_PLAN.md). Do not label the revised guide as proven or universally intuitive.

## Next implementation target

Build a coherent vertical journey: first item selection → quantity/unit correction → cash or part-credit payment → saved receipt → new sale, including offline and unconfirmed-transfer variants. Use real local persistence when claiming working offline behavior. Visually review at small laptop sizes before expanding the screen catalogue.
