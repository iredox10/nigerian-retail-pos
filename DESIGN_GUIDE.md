# Desktop Retail POS — Design and Interaction Guide

Version: 1.1 · 11 November 2026

Status: Proposed design baseline for the first desktop implementation. Product name and final brand identity remain undecided. This guide defines an original direction informed by the references below; it is not a claim that a finished interface has been built or merchant-tested.

Audience: every designer, developer, and AI working on this product.

Related documents:

- [Complete feature scope](FEATURE_SPECIFICATION.md)
- [One-page design brief](design/DESIGN_BRIEF.md): read first before drawing a screen
- [Machine-readable design tokens](design/tokens.json)
- [Canonical interface wording](design/copy.json)
- [Design review and changes](design/DESIGN_REVIEW.md)
- [Merchant usability test plan](design/USABILITY_TEST_PLAN.md)
- [Contributor instructions](AGENTS.md)

## 1. Design brief and product promise

Design a modern Kotlin/Compose desktop trading workspace for Nigerian shopkeepers, cashiers, stockkeepers, and business owners. Prioritize Windows checkout, inexpensive shop computers, keyboard/scanner input, touch-capable screens, interrupted connectivity, and familiar cash/transfer/credit workflows.

The product should feel precise, dependable, immediate, and easy to learn. Do not equate unfamiliarity with software with unfamiliarity with trade. Merchants already understand goods, quantities, agreed prices, money received, and money owed. Organize the interface around those concepts.

Beauty comes from disciplined composition, readable numbers, and satisfying control. Small type, cryptic icons, hidden actions, and excessive data density are not signs of sophistication.

The promise: **Know what you sold, what you received, what is left, and who owes you—even without internet.**

The distinctive interaction is a **persistent sale workspace**: choose goods on the left, inspect a receipt-like sale on the right, then take payment in that same right-hand region. A quiet status strip explains saved work in everyday language. Units and payment certainty stay explicit throughout.

### The first-glance contract

Without an introduction, a first-time user should be able to point to:

1. Where to choose/search for goods.
2. The goods already in this sale and how to change their quantity.
3. The total the customer needs to pay.
4. The next action to take payment.

The interface must also distinguish a saved sale from confirmed money received. These are design acceptance goals to test with merchants, not established results.

### Three levels of detail, one consistent product

- **Always visible:** item identity, selling unit, quantity, price, total, next action, and any current financial risk.
- **One action away:** change price, add customer, put sale aside, return to a previous sale, add stock, record debt payment.
- **Back-office detail:** tax rules, costing policy, account categories, settlement matching, sync operations, device management, and bulk tools.

Do not create a separate patronizing "beginner app" or silently move controls as users gain experience. Keep one stable layout with optional shortcuts and denser reporting views.

Do not introduce a product name, logo, slogan, or fabricated customer endorsement as an approved brand decision.

## 2. Research, reference lock, and evidence limits

Live Refero tools were unavailable. Research used the skill's typography, color, component-craft, and anti-pattern references, plus the official product/design documentation listed here. No signed-in competitor applications or finished app screenshots were visually audited in this pass. The first rendered implementation must undergo visual review.

| Reference | Bounded contribution | What we adapt | What we do not inherit |
|---|---|---|---|
| IBM Carbon data-table guidelines [R1] | Primary structural reference for back-office screens | Table-first information, stable headers, compact toolbars, sorting, selection, and accessible density | Carbon branding, exact palette, or compulsory React components |
| Shopify POS smart-grid documentation [R2] | Checkout speed | Merchant-configured product/action favourites | Its complete navigation or commerce ecosystem |
| Square split-payment documentation [R3] | Payment sequencing | Visible payment portions and remaining balance | Its timeout policies, provider restrictions, or US payment assumptions |
| Refero bundled craft references [R4] | Visual foundation | Neutral product typography, semantic colors, restrained surfaces, clear focus and form states | Marketing page composition or arbitrary decorative effects |
| W3C WCAG 2.2 [R5] | Accessibility benchmark | Contrast, visible/unobscured focus, keyboard operation, meaningful labels | A claim of formal desktop conformance without testing |
| W3C cognitive accessibility [R7] | First-time comprehension | Familiar controls, clear purpose, visible relationship between action and result | An assumption that traders have cognitive disabilities |
| NN/g recognition and recall [R8] | Learnability | Visible choices and contextual help instead of memorized commands | A mandatory training tour |
| GOV.UK question-page guidance [R9] | Focused input | Ask only necessary questions and group related decisions | One new page for every field in repetitive checkout |
| User requirements and feature specification | Primary product authority | Offline-first retail, Nigerian units, debt, cash accountability, Kotlin desktop | Agent banking flows |

### Locked direction

- **Primary foundation:** a light, precise retail counter with generous reading size. Carbon owns back-office table structure only; its dense enterprise layouts must not determine checkout density.
- **Preserve:** strong text contrast; orderly aligned rows at comfortable default density; thin separators; fixed checkout total/action; one clear main action per task.
- **Borrow only:** configurable favourites from Shopify; split-payment remainder visibility from Square; recognition and clear-purpose guidance from R7–R9.
- **Original commitments:** cool neutral canvas, white work surfaces, dark ink, restrained deep-teal actions, and explicit data-certainty labels.
- **Media:** optional real product thumbnails only. Missing photos use a simple consistent package icon. No stock photography or generated lifestyle art in operational screens.
- **Reject:** dark developer-tool imitation, glass surfaces, gradient dashboards, decorative charts, pill-shaped everything, and marketing hero sections inside the app.

### Decision ledger

| Decision | Source | Reason |
|---|---|---|
| Persistent products/basket layout | User's checkout convenience requirement | Reduce context changes during a sale |
| Configurable favourites | R2 | Put frequent items within immediate reach |
| Tables for inventory and ledger work | R1 | Compare many records accurately |
| One sans family and tabular figures | R4 | Clear scanning and stable monetary alignment |
| Light default and sparse accent use | R4 + bright-shop usability assumption | Preserve contrast without decorative noise |
| Deep teal main action | Original proposal constrained by R4 color-role rules | Distinguish action without turning the entire screen green |
| Separate local-save, sync, and payment states | User's offline requirements | Prevent incorrect claims about money or data safety |
| Units visible in basket and receipt | Feature specification | Prevent carton/piece and measurement mistakes |
| Keyboard parity and touch mode | Desktop/scanner requirement + R5 | Support different counter setups |
| No dashboard as cashier landing screen | Cashier's primary task | Open the app ready to sell |
| Five everyday destinations with advanced tools under More | First-glance requirement + R7/R8 | Avoid presenting the entire feature catalogue at once |
| Larger defaults and visible quantity/unit controls | First-glance requirement + R5/R7 | Make entry and correction discoverable |
| Question-led payment and plain-language status | First-glance requirement + R7/R9 | Explain the current decision without internal terminology |

The exact palette and measurements are proposed design decisions, not values copied from a competitor. Validate them on real hardware and with merchants.

## 3. Rules that apply to every screen

1. Protect the user's money, stock, and recorded work before optimizing visual simplicity.
2. Never make ordinary locally supported work wait for network access.
3. Put the next useful action where the user is already looking.
4. Use the canonical everyday labels in `design/copy.json`; internal domain names need not match user-facing wording.
5. Expose complexity progressively; do not hide essential amounts, units, balances, or status.
6. Do not communicate a state through color alone.
7. Show a local completion only after durable persistence succeeds.
8. Preserve drafts, search, filters, and scroll position when opening and closing details.
9. Prefer an inline change or side panel to a sequence of popups.
10. Advanced modules must not make an ordinary cash sale harder.
11. Do not implement dead buttons, simulated sync, fake success, or fabricated report figures in production.
12. A disabled action explains what is missing and how to proceed.
13. Every selling action must be discoverable by looking and clicking; shortcuts only accelerate it.
14. Keep the receipt total, location of the main action, and quantity controls spatially stable.
15. Explain a financial consequence before commitment, not through a warning discovered afterward.

## 4. Visual identity and color tokens

Use semantic tokens from `design/tokens.json`; do not scatter literal colors through screen code.

| Token | Value | Role |
|---|---|---|
| canvas | `#F4F6F8` | Window workspace behind sections |
| surface | `#FFFFFF` | Main working surface, basket, dialogs |
| surfaceSubtle | `#F8FAFC` | Table headers and quiet navigation surface |
| textPrimary | `#182230` | Body, names, headings, monetary values |
| textSecondary | `#536174` | Supporting descriptions |
| textMuted | `#64748B` | Readable timestamps and metadata |
| borderSubtle | `#DCE2E8` | Nonessential separators |
| borderControl | `#7A8796` | Input/control boundaries requiring visual identification |
| accent | `#087F73` | Main action and selected navigation text |
| accentHover | `#06695F` | Hover on solid accent action |
| accentPressed | `#05564F` | Pressed action |
| accentTint | `#F1FAF8` | Quiet selected navigation/background |
| onAccent | `#FFFFFF` | Text on solid accent |
| focus | `#2563EB` | Keyboard focus ring, distinct from selection |
| successText / successBg | `#067647` / `#ECFDF3` | Confirmed successful operation |
| warningText / warningBg | `#92400E` / `#FFFBEB` | Action needed or unverified payment |
| dangerText / dangerBg | `#B42318` / `#FEF3F2` | Failed operation, destructive action, overdue exception |
| infoText / infoBg | `#175CD3` / `#EFF8FF` | Neutral information and active syncing |

- White text on the main accent is approximately 4.89:1 contrast. Secondary text on white is approximately 6.31:1; muted text on white approximately 4.76:1. These pairs were calculated; every additional combination must be checked.
- Muted text is for white surfaces; use secondary text on the canvas or verify the actual background pairing.
- Subtle separators are not sufficient as the only boundary of an interactive control. Use the control border or another sufficiently contrasting affordance.
- Accent means action/selection. It does not mean a bank payment is confirmed.
- Success requires text plus an icon/state label; it may not be inferred from the accent color.
- Debt normally uses ink-colored figures; amber denotes due-soon attention and red denotes an actual overdue/error condition.
- Do not tint entire tables or every statistic with semantic colors.
- Light theme is the initial visual target. Dark theme is optional later and requires its own approved, contrast-tested token set; never automatically invert colors.

## 5. Typography and numerical presentation

Use **IBM Plex Sans**, bundled with the application so it never requires an online font fetch. Include its license. Use regular 400, medium 500, and semibold 600 weights. Verify the naira glyph, long product names, and any localized diacritics. A platform sans fallback is for recovery only.

| Role | Size / line height | Weight |
|---|---|---|
| Metadata and secondary labels | 14 / 20 | 400–500 |
| Compact table/secondary labels | 14 / 20 | 400–500 |
| Default interface and form values | 16 / 24 | 400 |
| Section title | 18 / 24 | 600 |
| Page title | 24 / 32 | 600 |
| Basket/payment total | 40 / 48 | 600 |
| Optional customer-facing total | 48 / 56 | 600 |

- Sizes are logical units; Compose uses `sp` for text. Support user text scaling and Windows display scaling.
- Use tabular numerals for quantities, money, tables, receipts, and counters. Verify actual font feature behavior on each runtime.
- Align monetary columns to the right and descriptions to the left.
- Default money formatting: `₦35,000.00`; preserve consistent precision within a table. Offer whole-naira display only where it does not conceal a real fractional amount.
- Never abbreviate actionable checkout totals as `₦35k` or hide digits with ellipsis.
- Unit labels always accompany quantities: `2 cartons`, `7 bottles`, `1.5 kg`.
- Show negatives with a minus sign and an explanatory label when the meaning could be ambiguous.
- Use explicit dates such as `11 Nov 2026`; show timestamps in the business timezone and distinguish actual entry time from a backdated business date.
- Operational labels, payment status, and save status are at least 14 sp by default. Product names, price/unit choices, primary controls, and entry values are at least 16 sp. Financially decisive figures must not become fine print.
- Do not use all caps for ordinary navigation or questions. Reserve uppercase for short identifiers where appropriate.

## 6. Spacing, shape, icons, and elevation

- Spacing scale: 4, 8, 12, 16, 24, 32, 48 logical units.
- Standard page padding: 24; compact page padding: 16.
- Section gap: 24; related field gap: 12–16; label-to-field gap: 8.
- Radius: 6 for inputs/buttons, 8 for small surfaces, 12 for dialogs/panels. Status badges may be pill-shaped.
- Borders: 1 logical unit; focus indicator: 2 units plus 2-unit offset where supported.
- Flat tables and divided sections are the default. Use contained surfaces only when they define a separate interaction region.
- Shadows are limited to overlays, menus, and floating surfaces. No shadow on every row or product.
- Use one consistent outline icon family, preferably Lucide assets with consistent stroke and licensing. Bundle locally; choose a maintained Compose-compatible route or approved vector resources.
- Icons: 18 for dense rows; 20 for navigation/buttons; 24 for touch controls.
- Label important actions. No emoji as navigation or action icons.

## 7. Window layout and density

Primary review size: **1440 × 900 logical units**. Also review **1366 × 768**, **1280 × 720**, and a compact **1024 × 600** viewport. Test actual Windows scaling separately; physical pixels and logical units are not interchangeable.

### Standard desktop shell

- Desktop starts in true fullscreen at the user's request. Provide a 40-unit draggable title strip with Minimize, Exit fullscreen/Fullscreen, and Close, plus F11 to toggle. Windowed mode remains available; never trap users in fullscreen. Do not hardcode the display resolution or change the user's monitor settings.
- Left navigation: 208 units; minimum labeled width 176. Do not automatically collapse it into unexplained icons.
- At compact widths, replace it with a clearly labeled **Menu** button opening the same labeled navigation. An icon-only rail is not the default.
- Top context bar: 56 units. Show page identity and staff/lock; shop location/counter is quiet context, not a prominent selector during a sale.
- Global search remains available from a labeled navigation utility/command menu; do not put it next to product search as a competing search box.
- Bottom status strip: 36 units with readable save/connection wording. Keyboard hints appear beside the relevant control or in help, not as a permanent technical command bar.
- Content expands to fill available space; no marketing-style centered narrow canvas.
- Keep the current task visible while details open.
- Scope filters apply consistently to all visible totals and tables.

### Input density

| Element | Standard | Touch mode |
|---|---|---|
| Field/button height | 44 | 48 |
| Primary checkout action | 56 | 56 |
| Table row | 52 | 56 |
| Minimum icon-button hit area | 44 × 44 | 48 × 48 |
| Product tile minimum | 156 × 112 | 176 × 128 |

Touch mode is a persistent device preference. A single screen must not randomly mix compact and touch dimensions.

An optional compact **reporting** density may use 44-unit rows and 40-unit controls. It must be explicitly chosen and does not shrink checkout or affect a novice user's default view.

At narrow widths, move navigation behind Menu first, switch products to a list, and reduce nonessential columns. Below the space needed for a readable two-pane checkout, switch to explicit **Choose items / This sale** views with a persistent total and sale line count. Never shrink money or hide the primary action to force the wide layout.

## 8. Navigation and information architecture

Cashiers land on **Sell**, with their last unfinished basket restored. Owners may choose **Overview** as their default.

| Everyday destination | First view and plain-language actions |
|---|---|
| Sell | Choose items; This sale; Saved sales |
| Sales | Recent sales; Find a receipt; Return items |
| Stock | Items in stock; Add stock; Count stock |
| Customers | Customers; Who owes you; Record payment |
| Money | Today's cash; Record expense; Money in/out |

**More** is a sixth labeled disclosure, not an unlabeled ellipsis. It opens an organized list of Orders, Suppliers, Reports, Bookkeeping, Staff, Shop settings, Locations & devices, and other enabled modules. Reports may be pinned by an owner; never show all advanced children permanently in the main sidebar.

- Owner configuration can pin a small number of additional destinations. Do not add a new sidebar row for every feature.
- An authorized cashier sees only the applicable everyday destinations; removing unauthorized features must not reorder the remaining ones during a shift.
- Stock movements and transfers live under Stock; settlements and bookkeeping under Money/More. Preserve these paths in breadcrumbs and help.
- Suppliers also have a contextual link inside Add stock. Money owed by customers is accessible from Customers and a clearly labeled Money summary shortcut; all paths open the same ledger.
- Keep **Help** and **Saved work & backup** in utility positions. Their names must not require understanding the word "sync".
- Each section opens with one explanatory sentence if its purpose is otherwise unclear. Do not add onboarding paragraphs to every familiar screen.

- Hide unavailable modules and restrict financial access by role.
- Retain discoverable entry points for authorized setup; do not advertise unusable advanced features to cashiers.
- Keep technical sync diagnostics inside Saved work & backup, not in the primary navigation.
- Customer debt is a tab/view inside Customers, not a separate disconnected database.
- Navigation badges indicate actionable exceptions, not arbitrary engagement counts.
- Context changes while a basket exists must explicitly keep, hold, or discard the basket. Never move a live sale silently to another branch/register.

## 9. Checkout composition

Wide-screen wireframe; proportions adapt using section 7 rules:

```text
┌──────────────────────────────────────────────────────────────────────────────┐
│ OS title bar                                                                │
├───────────────┬──────────────────────────────────────────────────────────────┤
│ Adewale Stores│ Sell · Counter 1                               Amina · Lock │
│               ├──────────────────────────────────────┬───────────────────────┤
│ Sell          │ Search for an item                    │ This sale             │
│ Sales         │ Frequent items   All items            │ Add customer          │
│ Stock         │                                      │                       │
│ Customers     │ Choose items                          │ Malt drink            │
│ Money         │ Malt drink                            │ 2 cartons · ₦24,000.00│
│               │ ₦12,000.00 / carton   [Add to sale]   │ [−] 2 [+] [carton ▾] │
│ More          │                                      │ 7 bottles · ₦3,500.00 │
│               │ Add an item not listed                │ Total     ₦27,500.00  │
│               │                                      │ [Take payment →]      │
│ Help          │                                      │ Put sale aside        │
├───────────────┴──────────────────────────────────────┴───────────────────────┤
│ Saved on this computer · No internet — you can keep selling       Details   │
└──────────────────────────────────────────────────────────────────────────────┘
```

- Basket width: 360–400 units on wide screens; minimum 320 before switching layout.
- Above the goods, show **Choose items**. The right-hand heading is **This sale**. These are signposts, not a three-step wizard repeated on every screen.
- Product search stays at the top, labeled **Search for an item** with one short hint: `Type a name or scan a barcode`. Match names, aliases, barcode, and SKU internally without demanding those terms in the prompt.
- The starting view shows a merchant-pinned Frequent items shelf and an obvious All items view. Empty favourites must not imply an empty catalogue. Categories are filters within All items, not a competing third navigation layer.
- Frequent items can be simple tiles; full catalogues use a readable list. A user's explicitly chosen view stays stable. Do not automatically rearrange popular items during trading.
- Show product name, distinguishing size/variant, and **price per selling unit**, e.g. `₦500.00 / bottle`. Show available stock as quiet supporting information with freshness when relevant.
- Use a real photo if available, but names and packaging text must remain sufficient. Without photos, use a consistent small icon or neutral monogram plus text; never make random tile colors the way products are identified.
- Rows have an explicit **Add to sale** control. Tile views have an unmistakable add affordance; keyboard/scanner addition remains available. Neutral add controls must not compete with the single teal Take payment action.
- Adding goods updates a visible `In this sale: 2 bottles` indicator where space permits. Optional quiet scan sound is configurable; visual feedback always exists.
- An item with one selling unit adds immediately. If there are multiple valid units without a unique barcode mapping or merchant-set default, ask once in a focused chooser: **How are you selling it?** List `Bottle — ₦500.00` and `Carton (24 bottles) — ₦12,000.00`. Do not silently infer a new default from the previous customer's sale.
- Basket lines show readable name, amount, quantity with visible minus/plus controls, and labeled unit selector. Quantity is also directly editable. Unit changes do not silently reinterpret `2 cartons` as `2 bottles`; show the resulting quantity, amount, and conversion before applying.
- Minus at quantity one exposes a clear Remove item action rather than silently removing on an ambiguous click. Draft removal offers Undo; completed transactions use the return/correction flow.
- Show conversion help beside unit editing: `1 carton contains 24 bottles`.
- Fixed receipt footer contains total and **Take payment**, with the total repeated in the payment header. Both have stable positions; only basket items scroll.
- Core checkout needs no generic OK confirmation after the explicit final completion action.
- Show applied discounts and taxes as readable line items; do not fill every empty sale with zero-value accounting rows. **Change price** and **Discount** are discoverable in the line editor/sale options, not cryptic edit icons.
- **Add customer**, **Put sale aside**, and **Add an item not listed** are visible labeled actions. **Saved sales (2)** retrieves held baskets. Blank names for saved baskets get a time/reference; no mandatory customer name.
- No chart, promotion banner, or owner profit statistic inside cashier checkout.
- Empty basket: **No items yet** with `Choose an item on the left to start this sale`. Take payment is inactive with that reason, not an unexplained grey button. A returning user need not dismiss a tutorial.

### Where the visual distinction comes from

- A clean shelf-to-receipt composition: the receipt is white, bounded by one fine seam, with aligned item amounts and a strong total above its main action.
- The amount, not a decorative graphic, is the dominant visual anchor. Use 40 sp tabular figures with comfortable surrounding space.
- Keep one deep-teal main action in the sale/payment region. Product imagery can carry natural color without tinting every surface.
- Give typography a clear rhythm: 24 sp task title, 16 sp working text, 14 sp supporting text. Stable baselines and consistent 24/16/8 spacing create finish.
- Use one small transition to connect item addition to its changed quantity; no bouncing cards, artificial paper tears, gradients, or animated currency counting.
- Keep amounts selectable and names readable. No decorative uppercase label, oversized welcome greeting, empty chart, or ornamental illustration should compete with the work.

## 10. Checkout and payment journeys

### Normal cash sale

1. Search/scan/select products; default focus returns to product entry after each addition.
2. Resolve variant/unit ambiguity before adding; repeated unambiguous scans increment quantity.
3. Take payment changes the right-hand region into a focused payment workspace, keeping total/item summary and **Back to items** visible. Avoid a separate unanchored modal for each payment step.
4. Ask **How is the customer paying?** Show Cash, Bank transfer, Card terminal, and Pay later as labeled choices; show **Use more than one method** separately below. No method is silently recorded by default. The merchant may explicitly configure an initial expanded method, but recording still requires confirmation.
5. Choose Cash; reveal **Cash given by customer**, **Total**, and **Change to give** in the same region. The **Exact amount** shortcut fills the field only when deliberately chosen. Default **Change to give** remains unknown until a valid received amount is entered.
6. With enough cash, show the exact result and **Finish sale**. If cash is short, show **Still to pay ₦…** and the explicit choices **Add another payment** or **Customer will pay later**; do not silently convert short payment into debt.
7. Save sale, payment, stock, and sync entry atomically. Disable repeat submission while committing.
8. Receipt state shows **Sale saved**, the receipt number, **Paid in cash**, and prominently **Change to give ₦…** until New sale is chosen. The clerk must not need to remember change after the screen moves.
9. Offer **Print receipt**, **Share receipt**, and primary **New sale**. Do not force a receipt-choice dialog before the next customer. Remember merchant print preference; printing is a separate status.
10. New sale restores search focus. Printer failure offers Reprint; it does not repeat the sale. Leaving the receipt view never clears a still-unresolved external payment.

Once a method is chosen, collapse the unused method list into a labeled **Change method** control; retain the selected method and amount. Do not stack four expanded method forms into the 380-unit pane. On short screens, method-specific fields scroll while the sale total, Back to items, and final action remain reachable. An in-flight external attempt cannot be discarded by changing method.

### Bank transfer / external card terminal

- Payment screen shows total, receiving details where configured, reference, and verification state.
- Distinguish **Record external payment** from a provider-integrated payment request.
- Front-facing status is **Transfer not confirmed** or **Card payment not confirmed**. Explain `This app has not confirmed that the money arrived.` Manual-record and provider-check details follow underneath.
- Offline copy: `No internet. We cannot check this transfer now.` If permitted, offer **Record without bank confirmation** with `Only continue if you have checked the receiving account yourself.` This instruction does not verify money or authorize an otherwise restricted action.
- Manual recording is explicit, permission-controlled, reasoned where required, and remains **Transfer not confirmed** with `Recorded by Amina` until provider verification/reconciliation justifies an updated state. Reconnecting alone does not change it.
- A manual amount may count toward the merchant's recorded collection balance, but must be visibly separate from verified receipts. It must not imply final bank receipt.
- Provider confirmation and completed settlement are different states.
- If the provider outcome is unknown, preserve the attempt and offer Check status. Never label it failed merely because a network request timed out.
- Never suggest repeating an uncertain debit as a fresh payment.
- Do not show a large green Paid badge for an unverified transfer.

### Split payment and credit

- Payment portions are editable rows: method, amount, status, reference, and action.
- Show **Sale total**, **Cash**, **Bank transfer** (with confirmation state), other portions, and **Still to pay** throughout. Do not use `tender`, `allocation`, `receivable`, or `settlement` to explain the cashier's current decision.
- If unconfirmed electronic portions cover the remainder, say **Full amount recorded · Transfer not confirmed**, not **Paid** or an unqualified zero balance. Keep the unconfirmed amount visible; do not misclassify it as customer credit unless an authorized credit action is actually recorded.
- Example: `₦35,000.00 total · ₦20,000.00 cash · ₦15,000.00 on credit`.
- Credit requires an identifiable customer, displayed prior balance, new balance, due date where applicable, and permission checks.
- Show locally known balance and its scope; do not promise a globally current limit across disconnected devices.
- Completion CTA is **Finish sale** for a fully recorded cash/supported confirmed electronic sale, **Save with ₦… owed** when credit is being granted, and **Save without bank confirmation** for an approved unconfirmed electronic payment. Use adjacent full summaries; do not hide a risk inside a long tiny button label.
- Track a paid portion even if another portion fails. Do not pretend a real external payment was cancelled when only the local UI was closed.
- Change owed is a distinct liability with a customer/reference; never call it a discount or revenue.

### Give credit, record a repayment, or put the sale aside

- **Pay later** asks **Who will pay the balance?** Show customer selection, amount owed after this sale, and **When should they pay?** only if due dates are used. Name is required; phone is optional unless business policy says otherwise. Do not force email, a full address, or signup.
- **Record payment** in Customers means money received against existing debt. Show customer, amount owed, payment amount/method, and new debt before saving. This is not another sale.
- Keep **Customer owes you** and **You owe customer** separate. Do not cancel them against each other without a recorded, authorized action.
- **Put sale aside** keeps a draft for later and releases the till for another customer; it is not a credit sale and does not pretend payment or stock deduction has completed. Reservation policy must be explicit.
- Undo is for reversible draft edits; after a completed financial event, offer the appropriate correction/refund flow rather than pretending the record never existed.

## 11. Keyboard, scanner, mouse, and touch behavior

Initial shortcut map; test against OS, accessibility tools, and target keyboards before release:

| Shortcut | Action | Scope |
|---|---|---|
| F2 | Focus product search | Sell |
| F4 | Add/select customer | Sell |
| F6 | Put sale aside / open Saved sales | Sell |
| F8 | Open payment | Sell; never directly commits |
| Ctrl+K | Command/search menu | Application, outside sensitive entry |
| Ctrl+P | Print current saved receipt | Receipt context |
| Ctrl+L | Lock register | Application |
| Esc | Close current overlay/back one level | Never discards a persisted payment |
| Tab / Shift+Tab | Next/previous control | All screens |
| Enter | Activate focused action | Financial finalization still requires explicit focused confirmation |

- Show shortcuts in menus and a searchable help panel; every command also has a clickable control.
- Use visible focus and a logical tab sequence. Restore focus to the initiating control when an overlay closes.
- Barcode input must not steal focus while editing quantity, price, customer data, or payment amounts.
- Only route scans in a known product/scanner context or through an explicitly configured scanner input mode.
- Unknown barcode shows an inline result with permitted Add product and Search alternatives actions; it does not open repeated alerts.
- No essential action is hover-only or right-click-only.
- Confirmation and scanner suffix keys must not accidentally submit a payment.
- Provide paste, selection, copy account details, and ordinary numeric keypad behavior.

## 12. Offline, saving, syncing, and recovery language

Treat **local persistence**, **network availability**, **server synchronization**, and **external payment status** as separate dimensions.

| Condition | Visible wording | Behavior |
|---|---|---|
| Local commit in progress | Saving on this computer… | Briefly prevent duplicate completion |
| Local commit successful | Saved on this computer | User may proceed |
| No connection; local records safe | No internet — you can keep selling | Alongside local-save state; do not block supported operations |
| Connected and actively exchanging | Updating your online records… | Quiet informational state; show only for meaningful duration |
| Acknowledged by server | Online records updated · 11:42 WAT | Timestamp reflects actual acknowledgement, not payment confirmation |
| Network exists but service unavailable | Saved here · Online update delayed | Retry automatically; expandable explanation |
| One change needs review | 1 record needs checking | Describe the affected sale/stock issue; open review queue |
| Local storage commit failed | Sale not saved | Blocking error; retain basket and explain recovery |
| Data already saved; printer failed | Sale saved · Receipt not printed | Offer reprint without creating another sale |

- Pending count means queued change operations, not sales; do not label 12 changes as 12 sales.
- Pending operation counts, "sync", server identifiers, and queue details are technical information for the details panel. The ordinary footer explains what the merchant needs to know, not the implementation.
- Say **Saved**, not **Safe** or **Backed up**, when data exists only on this computer. Do not imply that a lost or destroyed computer can be recovered before a backup exists.
- `You can keep selling` appears only when local storage, authorization, and required local data are actually usable. A local save failure overrides all reassuring footer messages.
- Offline alone is not a red error banner. Real backup risk or prolonged failure may justify an amber notice with specific guidance.
- Never display Online records updated based only on network connectivity.
- Saved work & backup panel: **On this computer**, **In your online account**, and **Backup copies** as distinct sections. Technical details may disclose last acknowledgement, pending counts by type, affected device, failures, Retry, Export recovery copy, and backup status.
- Conflict rows state the business effect, e.g. `Two registers sold the last bottles. Stock is 3 below zero.`
- Preserve both valid sales; offer a stock investigation instead of a generic merge selector.
- Explain stale data near stock, credit limits, cross-device returns, reports, and remote approvals.
- On restart restore drafts and unresolved operations; check whether a previous completion already committed before offering retry.
- Exiting after successful local save is allowed even with pending sync. Warn on an actually unsaved editor, active payment attempt, or destructive action that threatens local records.
- Device reset/logout cleanup must never silently remove pending transactions. Offer keep data, backup/export, or cancel as appropriate.
- Cloud backup and synchronization have separate status and recovery explanations.

## 13. Core reusable components

### Buttons and actions

- Primary: solid accent, white label, action verb. Normally one dominant primary per task region.
- Secondary: neutral border and ink text.
- Tertiary: plain labeled action for low-priority tools.
- Destructive: red only for genuine destructive/reversal tasks; name the object and consequence.
- Loading preserves width and label meaning. Disabled controls explain missing prerequisites nearby or accessibly.
- Do not put Remove, Refund, and Complete sale immediately beside one another with equal visual weight.

### Forms

- Persistent labels above inputs; placeholders are examples, never the only label.
- Put related fields together; essential fields first, optional settings progressively disclosed.
- Money inputs show currency; quantity inputs show unit and precision.
- Preserve user-entered formatting until validation/commit where possible; do not jump the caret while typing.
- Inline errors explain the correction; focus the first invalid field on submit.
- Validate calculations using exact decimal/scaled representations, not binary floating-point money.
- Clearly separate temporary editing from a saved business event.
- Ask one related decision at a time in unfamiliar setup/payment flows. Keep frequently repeated related entries together; do not turn every field into a separate page [R9].
- Do not require a field just because the database has a column. Label optional fields explicitly; ask for tax IDs, supplier terms, or customer addresses only in the workflows that need them.
- Do not prefill unknown stock, purchase cost, or received money with a misleading zero. Show **Not recorded** where absence is different from zero; explain the effect on stock/profit reports.

### Tables and lists

- Toolbar: search left, filters/view controls next, main action right.
- Persistent column headings, readable density, visible scrollbars, right-aligned numeric fields.
- Selection checkboxes have their own focus targets; row activation does not accidentally select or mutate.
- Show sort direction; preserve filters, sort, column choices, and scroll position per user/device where useful.
- Bulk-action bar appears only when records are selected; show count and scope before action.
- Expansion handles brief details. Use a side panel or full detail page for longer histories.
- Table-only horizontal scrolling is allowed for genuinely wide back-office data; page-wide horizontal scrolling is not.
- Virtualize large tables without breaking keyboard navigation or selected records.
- Explicit `No matching products` versus `No products yet`; offer Clear filters versus Add/import product.

### Panels, dialogs, and feedback

- Side panel: 400–480 units for short edits and contextual details; full page for complex purchasing/counting.
- Dialog: 440–560 units for focused confirmation or short input; support scrolling and a visible footer at small window sizes.
- One modal layer at a time. A manager approval replaces or clearly nests within one managed flow; never stacks arbitrary windows.
- Trap focus appropriately, label the dialog, support Esc where safe, and restore focus on close.
- Toasts are brief and nonessential. Financial failure or a sync conflict remains visible until addressed.
- Success can be quiet; no confetti, bouncing totals, or congratulatory screens between customers.

## 14. Screen-by-screen functional contracts

| Screen | Layout and primary action | Critical details/states |
|---|---|---|
| First-run setup | Shop identity/access → first item/import → ready to sell | Only necessary questions; optional online linking/backup setup clearly offered; no forced full catalogue import |
| Staff unlock | Staff identity + PIN; Unlock register | Offline authorization status; recovery; no forced online OTP for every shift |
| Sell | Choose items + This sale; Take payment | Units, Saved sales, payment certainty, stock freshness |
| Sales | Searchable list/table + receipt detail | Paid/part paid/amount owed/transfer not confirmed separated; reprint/return linked to original |
| Orders | Filtered list + fulfilment detail | Deposit, reserved stock, remaining amount, partial dispatch |
| Stock | Item table + editor | Variant/unit prices, local stock, cost visibility permission |
| Add stock | Who supplied it + item quantities/costs; Save stock received | Buying units, total paid, supplier amount owed; optional partial order and cost details |
| Count stock | Location + What did you count? | Blind count option, cutoff time, intervening movements, difference review |
| Move stock | Send/receive timeline | From/to locations, in-transit quantities, discrepancies |
| Stock history | Goods in/out ledger | Source document, quantity/unit, actor, timestamp; no unexplained balance edits |
| Customers | Searchable list + Who owes you view | Customer owes you vs You owe customer; Record payment |
| Suppliers | List + purchase/payment ledger | Payables, credits, due dates; supplier payments are not automatically expenses |
| Today's cash | Current shift ledger + Count cash | Opening, money in/out, cash expected, cash counted, difference and handover |
| Expenses | Table + short entry form | Category, amount, payment source, attachment, business date |
| Bank payments | Confirmation and Money received tabs | Unmatched payments, fees, pending attempts, actual net settlement; accountant detail disclosed separately |
| Bookkeeping | Account/report navigation | Estimates flagged; drawings/contributions separate; period controls |
| Overview | Compact financial summary + useful exceptions | Sales vs collections vs profit; scope and last sync; no fake trends |
| Reports | Report selection + filters + table/chart | Explain inclusion/exclusion and data completeness; printable/exportable |
| Staff | Roles/access list + edit panel | Sensitive permissions, locally cached authorization expiry, history |
| Locations & devices | Location/device list + health | Last online update, local risk, enrollment, revocation delay |
| Saved work & backup | On this computer / Online account / Backup copies | Separate sync from backup; accessible recovery actions |
| Settings | Searchable grouped sections | Business, selling, units, taxes, hardware, locale, appearance, security |
| Help | Searchable local help + support draft | Offline articles; queued support request; diagnostic consent |

Owner Overview is limited to a small number of relevant metrics above a table/list of exceptions. Charts appear only where they answer a question, with text/table equivalents and labeled periods.

### Everyday money and stock presentation

- Today's cash begins with **Cash expected**, **Cash counted**, and **Difference**. Explain `₦1,000.00 less than expected` instead of relying on a red `−₦1,000` with no context.
- Money shortcuts are verbs: **Record expense**, **Add money**, **Take money out**, **Count cash**. Taking owner money out is explicitly separate from a business expense.
- Stock starts with Name, Available quantity + unit, and Selling price; reorder/expiry flags appear only when relevant. Cost, valuation, supplier terms, and bulk tools remain available to permitted users in detail/report views.
- Add stock begins with **What came in?**, buying quantity/unit, **Buying price per [unit]**, and **How much did you pay?**. Optional supplier credit is explicit; purchase order references and allocated delivery costs are disclosed details.
- Customer page shows **Who owes you**, name, amount owed, and last payment. Opening a customer exposes the history in date order and one primary **Record payment** action.
- Overview labels **Sales**, **Money collected**, **Customers still owe**, and **Estimated profit** distinctly. An unconfirmed transfer is not silently included as verified bank money.

## 15. Advanced module presentation

- Loyalty: optional customer-section balance and redemption action; show local freshness and offline redemption limits.
- Online storefront: separate sales channel and order source; no claim that disconnected stock is live online inventory.
- Delivery: recipient, landmark, dispatch status, proof, and collection reconciliation inside Orders.
- Beverage containers: separate goods, container quantity, and deposit rows; never mix container liability with product profit.
- Fabrics/foodstuff: explicit fractional unit and conversion help; do not assume local measures are standardized.
- Electronics: serial selection and warranty details only when the product requires them; repairs have their own service status.
- Restaurants: module-specific order/table/kitchen workspace; kitchen connectivity is separate from internet connectivity.
- Pharmacy: expiry/batch/quarantine states and specialist-reviewed workflows; no automated clinical advice.
- Promotions: explain the applied rule and final price. Never silently rearrange product favourites during a shift.
- Fiscal invoices: local document status and external validation status stay separate.
- Multi-branch overview: scope selector changes all report values together and names missing/stale branches.

## 16. Copy and Nigerian context

Use plain, respectful English. Do not stereotype merchants, use forced slang, or decorate the app with cultural motifs unrelated to the task. Local usefulness comes from units, prices, debt, addresses, and payment handling.

| Avoid | Use |
|---|---|
| Transaction persisted successfully | Sale saved |
| Network error; try again | Saved on this computer. Your online records will update when the connection returns. |
| Invalid input | Enter an amount greater than ₦0.00. |
| Balance | Customer owes / You owe customer / Cash expected, depending on meaning |
| Success | Cash sale completed / Repayment recorded / Stock received |
| Are you sure? | Refund ₦5,000.00 for receipt R1-0042? |
| Paid for a manual transfer | Transfer not confirmed · Recorded by Amina |

### Canonical vocabulary

| Internal/technical concept | Everyday interface wording |
|---|---|
| Catalogue / SKU query | Search for an item |
| Cart / basket | This sale |
| Hold / suspend ticket | Put sale aside; retrieve from Saved sales |
| Split tender | Use more than one method |
| Receivables / credit ledger | Who owes you; Customer owes you |
| Customer liability | You owe customer |
| Inventory receiving | Add stock |
| Unit of measure | Selling unit; Buying unit, with actual unit names |
| Variance | Difference; ₦… less/more than expected |
| Reconciliation | Check payments / Count cash, according to task |
| Sync queue | Saved work & backup; details only |

Technical terms are allowed in accountant/admin detail views with explanation. Do not replace them everywhere with imprecise synonyms. The canonical UI copy lives in `design/copy.json`; changing it requires updating this guide and associated journeys together.

- Example shop: Adewale Stores; branch: Main Market; register: Counter 1.
- Use realistic products and units, not generic Product A/B, for design fixtures.
- Use fictitious customer details and clearly labeled sample data.
- Show unfamiliar terms with short explanations, not long onboarding lectures.
- Do not claim that English literacy, confidence with computers, age, or business skill can be inferred from someone's occupation. Test wording and language preference with the intended users.
- Add language selection in Shop settings and accessible first-run entry. Translation is optional by rollout, not a reason to claim current support for languages not yet implemented.
- Localization must account for longer translations and language-specific glyphs.

## 17. Accessibility and interaction quality

- Benchmark contrast against WCAG AA: 4.5:1 for normal text, 3:1 for large text and necessary nontext boundaries.
- Visible keyboard focus must remain unobscured by sticky footers, overlays, and scroll containers.
- Complete sale, record repayment, receive stock, and close shift must be possible using only a keyboard.
- Every meaningful icon/control has an accessible name and role; decorative icons are excluded from reading order.
- Announce local save, errors, and meaningful payment changes without continuously interrupting screen readers with sync progress.
- Support text scaling without truncating amounts or overlapping controls.
- Touch mode uses 48-unit targets as our product standard; do not mislabel that as the exact WCAG minimum.
- Provide non-color status cues and reduced-motion preference.
- The core sale must work without typing when the needed products and customer already exist: choose item, change quantity/unit, select payment, use Exact amount if appropriate. Search/scanning remain accelerators, not requirements for every user.
- No drag-only price/quantity controls, hidden swipe actions, long-press-only editing, or icon-only payment choices.
- Test Windows assistive technology using the packaging/runtime accessibility support required by the chosen Compose version. Do not assume Compose semantics alone guarantee desktop screen-reader support [R6].
- Offer usable keyboard paths even where screen-reader integration needs platform work.

## 18. Motion and perceived speed

- Hover/focus feedback: 100–120 ms. Small expand/collapse: 160 ms. Panel/dialog entrance: at most 180 ms.
- Use opacity and small positional transitions only where they clarify state.
- Product addition updates basket immediately after local state handling; no flying product animation.
- A brief local highlight can identify the updated basket line; it must not move amounts or rely on color alone. Quantity itself changes, with an optional accessible announcement.
- Do not animate currency counting, table reordering during editing, or financial success before commit.
- No looping sync animation while offline; animate only actual work.
- Use skeletons only for genuinely pending data, never to hide data already stored locally.
- Preserve layout while images load; product photos have fixed bounds.
- Performance goals to measure, not claim: common search response within 150 ms and local sale completion feedback within 300 ms on the agreed test catalogue/device. Investigate missed targets without weakening durability.

## 19. Compose and future web implementation rules

- Desktop uses Kotlin Multiplatform/Compose; local database and domain logic remain outside composables.
- Build shared theme primitives before screen-specific styling: colors, typography, spacing, shapes, dimensions, states.
- Suggested components: `AppShell`, `NavigationItem`, `PageToolbar`, `PosButton`, `MoneyText`, `QuantityField`, `ProductSearch`, `BasketLine`, `PaymentPortionRow`, `StatusBadge`, `SyncStatusStrip`, `LedgerTable`, `DetailPanel`, `ApprovalDialog`, and `EmptyState`.
- Component names are suggestions, not required architecture. Reuse roles and behavior instead of copying screen markup.
- Override Compose theme tokens; do not accidentally ship default purple controls, oversized mobile paddings, or mobile-only navigation on desktop.
- Use real focus handling, scrollbars, keyboard events, text selection, semantics, and platform file/print dialogs.
- Theme dimensions use `dp`, text uses `sp`; map JSON token roles explicitly.
- Money formatting and financial arithmetic share tested domain functions; never reimplement rounding per screen.
- Persist drafts and pending operations outside transient UI state. App restart must not depend on remembered composable state.
- Expose local-save/sync/payment state models separately rather than a single `isSuccess` flag.
- Future React/TanStack web uses equivalent semantic tokens and component roles. It may adapt layouts to browser needs but must preserve terminology and financial state distinctions.
- TanStack Query caching does not substitute for a durable offline transaction database.
- Heroku availability must not gate desktop local actions. Backend validation/reconciliation results are rendered as explicit subsequent states.

## 20. Required state coverage for every feature

Before calling a screen complete, define and implement applicable states:

- First use / no records.
- Normal populated state.
- Search with no matches.
- Local editing / draft restored.
- Saving locally / saved locally / local save failed.
- Offline with usable local data.
- Online with sync in progress / sync delayed / sync issue.
- Stale or incomplete cross-device data.
- Validation failure / permission denied / local approval required.
- External outcome pending or unknown.
- Success with next useful action.
- Long names, large naira values, fractional units, many line items, and missing images.
- Printer unavailable / disk nearly full / interrupted restart as applicable.

An offline financial error cannot be represented only as a disappearing toast.

### First use without a training dependency

- First launch introduces the next useful task, not a slideshow describing every feature.
- Collect the minimum shop identity and owner access information, then offer **Add your first item** or **Import items**. Add stock/costs separately where unknown; never invent opening figures to make setup appear complete.
- A small optional **Try a practice sale** entry opens visibly separate sample data with no effect on real stock, receipts, customer debt, or payment partners.
- First useful sale requires no tax configuration, dashboard tour, customer account, supplier address, or internet registration unless that specific operation legally or technically requires it.
- Contextual hints answer the question at the current control and can be dismissed. They must not cover the total or final action.
- The application remains understandable after those hints are gone. Do not count completion of a guided tutorial as proof of first-glance usability.
- Locking/unlocking changes staff identity without exposing the previous user's restricted money data. Restoring a basket does not grant new permissions.

## 21. Design fixtures for every implementation review

Use these internally consistent scenarios alongside additional stress data:

1. Cash sale: two cartons at ₦12,000 each plus seven bottles at ₦500 each = **₦27,500.00**. ₦30,000 cash received; **₦2,500.00 change**. Unit conversion: 24 bottles per carton. Starting 240 bottles; ending **185 bottles**.
2. Credit sale: total **₦35,000.00**; cash **₦20,000.00**; customer owes **₦15,000.00**. Later repayment **₦5,000.00** leaves **₦10,000.00**; sales remain ₦35,000.00.
3. Unverified transfer: **₦18,500.00**, recorded by cashier while offline. No provider-verified badge and no claim of bank settlement.
4. Cash close: opening ₦10,000 + cash received ₦60,000 − expenses ₦5,000 − supplier payment ₦20,000 = expected **₦45,000.00**. Counted ₦44,000; shortage **₦1,000.00**.
5. Sync conflict: two disconnected registers sell the last units; show both sales and an actionable negative-stock explanation.
6. Stress view: a long product name, 1.375 kg, a ₦12,345,678.90 amount, 60 basket lines, a 20,000-product catalogue, and no photos. No clipped digits or unbounded layout.
7. First-time trader: no photos, no scanner, no shortcuts, and no help tour. They must still find items, add one, correct its quantity, and find Take payment.
8. Short cash payment: total ₦35,000.00; cash ₦20,000.00. The app shows **Still to pay ₦15,000.00** and does not create debt until a named, authorized credit action is chosen.
9. Wrong unit: a product offers bottle and carton. Switching 2 cartons to bottles must expose the new quantity/price and cannot silently reinterpret the sale.
10. Credit receipt: **Sale saved · Customer owes ₦15,000.00**. Do not show Paid, Cash received ₦35,000, or a generic green success implying full payment.

Fixture values are invented for verification, not market pricing claims.

## 22. AI/developer workflow and design quality gate

### Before implementation

1. Read this guide, the feature specification, and project contributor instructions.
2. Identify the user's task, permission level, local data available, and offline/network dependencies.
3. List screen states and financial/stock effects.
4. Reuse existing approved components and tokens before inventing another style.
5. Name the applicable reference/requirement for any major new layout decision.
6. For a new visual direction, update the reference lock and decision ledger before coding; ordinary screen additions follow this baseline.
7. Read the one-page design brief and canonical copy before adding controls. First-glance clarity has priority over fitting more features on the screen.

### During implementation

- Build the smallest complete working journey for the selected feature, including real persistence and failure behavior.
- Keep unfinished features explicitly unavailable; do not create clickable-looking stubs that report success.
- Do not change shared tokens to repair one isolated screen; fix the layout or propose a documented system change.
- Do not mix draft basket removal with reversing a completed financial transaction.
- Do not introduce new user approval steps unless the task/domain permissions require them.

### Before delivery

- Render actual Compose screens and inspect them; code review alone is insufficient for visual acceptance.
- Capture checkout empty/populated, payment cash/split/unverified, stock table, customer debt, and offline/sync issue states as they become available.
- Review at the window sizes in section 7 and Windows 100%, 125%, and 150% scaling on appropriate hardware.
- Test keyboard-only and touch-mode workflows; verify focus after dialogs, printing, and New sale.
- Measure contrast, check money arithmetic, and verify all text/status combinations.
- Verify offline persistence, restart recovery, printing failure, and sync retry without duplicate financial effects.
- Inspect long content and the section 21 stress fixtures.
- Compare the rendered result to this reference lock. Fix inconsistent spacing, typography, action placement, and status semantics.
- Record screenshots, checks performed, known limitations, and whether merchant testing has happened. Never claim validation that was not performed.
- Test with actual merchants before claiming the product is the most convenient or universally best design.
- Run the scenarios and record evidence in [the merchant test plan](design/USABILITY_TEST_PLAN.md). A high visual score cannot cancel a critical misunderstanding of money, stock unit, or saved status.

### Rejection checklist

Reject a screen if it contains any of the following:

- Huge statistic cards or charts displacing the checkout task.
- No visible total/unit/payment certainty at the moment of commitment.
- Success before the local commit completes.
- Network loss blocking an ordinary cash sale with valid local authorization.
- Fixed-width layout clipping the basket or naira amounts.
- Faint gray text, invisible focus, or essential hover-only actions.
- Decorative gradients, glass effects, emojis, inconsistent icons, or random colors.
- Repeated nested dialogs for ordinary sale entry.
- A transfer screenshot treated as payment verification.
- A syncing spinner that falsely implies bank payment processing.
- Loss of draft or recorded data on navigation, restart, or sync conflict.
- More than the five everyday destinations plus More presented by default as a long sidebar catalogue.
- An automatically icon-only menu, compact checkout text, or two competing search fields.
- Routine cash sales requiring repeated generic confirmations or forced receipt dialogs.
- A new user needing a tutorial to find item entry, quantity, total, or Take payment.
- Automatic credit creation, silent unit reinterpretation, or an unconfirmed transfer labeled Paid.

## 23. References and provenance

Checked 11 November 2026. These references inform patterns; they do not imply endorsement or permission to copy proprietary assets.

- **R1:** IBM Carbon, Data table guidelines: https://www.carbondesignsystem.com/building-blocks/core/components/data-table/guidelines
- **R2:** Shopify POS, Smart-grid editing: https://help.shopify.com/en/manual/sell-in-person/shopify-pos/customize-pos/smart-grid-management/edit
- **R3:** Square, Split payments: https://squareup.com/help/us/en/article/5097-process-split-tender-payments-with-square
- **R4:** Local Refero Design skill and bundled craft references: `/home/iredox/.agents/skills/refero-design/SKILL.md`, `references/typography.md`, `references/color.md`, `references/craft-details.md`, `references/anti-ai-slop.md`. These local resources may not exist in another environment; this guide records the adopted rules so future contributors can still follow them.
- **R5:** W3C, WCAG 2.2: https://www.w3.org/TR/WCAG22/
- **R6:** JetBrains, Desktop accessibility: https://kotlinlang.org/docs/multiplatform/compose-desktop-accessibility.html
- **R7:** W3C, Help users understand what things are and how to use them: https://www.w3.org/WAI/WCAG2/supplemental/objectives/o1-understandable/
- **R8:** Nielsen Norman Group, Recognition and recall: https://www.nngroup.com/articles/recognition-and-recall/
- **R9:** GOV.UK Design System, Question pages: https://design-system.service.gov.uk/patterns/question-pages/

When references change, preserve the product's documented behavioral requirements and review any proposed design-system changes explicitly.

## 24. Icon and navigation refinement — 23 November 2026

The merchant requested icons and a more intuitive, polished interface. The existing light counter direction remains the reference lock. Lucide outline artwork uses a 24-unit grid, 2-unit stroke and round caps. Navigation pairs 20-unit icons with persistent text. Selected destinations use the teal tint. Shared action buttons pair recognizable icons with their existing labels. Control corners use 8 units, working panels use 12, and empty-state icon plates use 16.

Shop tools use responsive grouped cards for trading, records and workspace settings. Each card provides an icon, task name, short purpose and forward arrow. Permission filtering applies before rendering. Product-photo placeholders use package or shop symbols. Checkout retains its left goods area, right sale panel and fixed-width amount hierarchy.

Reference ledger follows Carbon R1 for repeated aligned work surfaces, Shopify R2 for recognizable task entry, Square R3 for the stable payment panel and local Refero icon guidance R4 for a single outline family, visible labels and semantic color. Tokens are recorded in design/tokens.json. These changes require rendered desktop and keyboard checks. Merchant comprehension research remains pending.

## 25. Merchant receipt design and money entry — 23 November 2026

Receipt appearance is controlled contextually in Shop & receipt details. Retain the app's established light/teal interface while the merchant chooses document-specific colours, Classic/Modern/Compact styles, None/Single/Double frames, heading alignment, logo visibility, tagline, returns note and additional artwork. The side-by-side preview uses the export renderer and clearly identifies its sample sale. Narrow layouts stack the form and preview. Keep recorded goods, totals, tender and payment status in text. Artwork follows the document content with its own reserved page space. Each sale captures the settings and references immutable artwork bytes.

The existing Carbon R1 form hierarchy and Refero R4 component guidance remain the editor's references. Merchant branding colours belong to the document. Thermal decoration uses black strokes and text so colour does not carry essential meaning. Physical printer acceptance remains required. Receipt corners and in-app previews use shared surface and overlay tokens.

Display saved money as naira with grouped thousands and two decimal places. Money entry shows a naira prefix and live grouping, preserving the raw entered digits, caret mapping and kobo precision. Counts, quantities, percentages and denomination counts keep their own numeric semantics.
