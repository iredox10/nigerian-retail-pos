# Desktop design brief — read before building

Version 1.1 · 11 November 2026

The full authority is [DESIGN_GUIDE.md](../DESIGN_GUIDE.md). This page is the short implementation brief, not a replacement for financial behavior or required states.

## The person and the task

Design for someone who understands their shop but may never have used retail software. Let them recognize goods, quantities, prices, payments, and debts. Do not require them to understand a software architecture or accounting vocabulary to make a sale.

## The screen in one sentence

**Choose items on the left. Check this sale on the right. Take payment below the total.**

Keep that structure while moving into payment: the right-hand area becomes a focused payment form, not a stack of unfamiliar popups. Return to items without losing the sale.

## What is visible

- Navigation: **Sell · Sales · Stock · Customers · Money**, then **More** for advanced destinations. Role restrictions may remove unavailable destinations, preserving order.
- Product area: **Choose items**, **Search for an item**, **Frequent items / All items**, readable item names, variant, and price per unit.
- Sale area: **This sale**, Add customer, quantity controls and selling units, total, **Take payment**, Put sale aside.
- Payment area: **How is the customer paying?** Cash, Bank transfer, Card terminal, Pay later; **Use more than one method** remains discoverable.
- Status: **Saved on this computer** and, when disconnected, **No internet — you can keep selling**, only if local work is actually usable.

## What makes it visually distinctive

- A light trading counter with white working surfaces, dark ink, and one deep-teal main action.
- A receipt-like sale panel: precise amount alignment, a single fine divider, and a 40 sp total. No fake paper tears or ornamental receipt texture.
- Real product pictures where available; equally usable text when they are not.
- A consistent rhythm of 24 sp headings, 16 sp working text, and 14 sp supporting labels.
- Comfortable 44-unit controls, a 56-unit main action, and 52-unit table rows. Compact reporting is optional; checkout is not tiny.
- Stable positions and subtle response to action. Polish comes from spacing, proportional balance, and interaction, not visual effects.

## What must never happen

- A sale reports completion before it is durably saved.
- A transfer becomes Paid because someone uploaded a screenshot or internet returned.
- A short cash payment becomes customer debt without choosing a customer and an authorized credit action.
- Changing unit silently changes what quantity means.
- A cashier must memorize icons, commands, or a tutorial to find the next step.
- A printer error prompts a second sale or a closed popup hides an unresolved debit.
- Ordinary offline operation is obstructed by continuous warnings or a long technical queue count.
- A dense sidebar, dashboard cards, or decorative animation takes space away from goods, total, and payment.

## First review

Before polishing details, show the unassisted screen to a new user. Can they find goods, quantity, total, and Take payment? Then ask what **Transfer not confirmed** means. If they cannot answer, fix the design before adding visual detail.

Use [the test plan](USABILITY_TEST_PLAN.md) to collect evidence. First-glance comprehension and designer approval are both untested until a real screen exists.
