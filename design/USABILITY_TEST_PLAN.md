# Merchant usability and visual-quality test plan

Status: **Not run.** No application screen or prototype has been tested with merchants yet.

Purpose: establish whether a trader unfamiliar with this app can understand its first screen, complete daily tasks, and correctly interpret money and saved-work states. Visual craft is reviewed separately; neither result substitutes for the other.

## 1. Participants and setup

- Start with one focused round of 6–8 participants across the intended initial shop types. Repeat after fixing problems. This is formative research, not a statistically representative survey of Nigeria.
- Include shop owners and assistants; mix low software familiarity and experienced POS users. Record role, prior experience, preferred language, and input-device familiarity without inferring ability from age or occupation.
- If the UI is in English, record whether the participant can comfortably read it. Language mismatch must not be reported as a successful comprehension test or disguised through unrecorded live coaching.
- Use the target laptop/desktop size, real keyboard/mouse, and touch hardware when relevant. Include 1366 × 768-class displays and Windows scaling checks.
- Use visibly fictitious sample data, no real transfers or customer debt. Get consent before observing or recording. Do not collect unnecessary personal or financial details.
- Reset the sale/data before each participant. Do not show onboarding, shortcuts, or explanations before the first-glance check.

## 2. First-glance check

Show the normal Sell screen with a populated catalogue and an empty sale for about 10 seconds. Keep it visible and ask the participant to point, not recall labels from memory:

1. Where would you start if someone wanted to buy a bottle of malt?
2. Where would you look to see what you have added?
3. Where would you change the number of bottles?
4. Where would you find the total and take the customer's payment?

Use neutral prompts; do not name the intended button in advance. Repeat with a populated sale so quantity/total questions are meaningful. Record first action, hesitation, wrong region, and any help. An empty screen alone cannot prove quantity-control comprehension.

Initial design gate: at least 80% of participants locate each applicable region without coaching in roughly 10 seconds per prompt. Convert percentages to an actual count for the round and report it. This is a proposed product threshold, not a published universal usability rule.

## 3. Practical tasks

| Task | Prompt/scenario | What correct behavior looks like |
|---|---|---|
| Cash sale | Sell 2 bottles at ₦500 each; customer gives ₦2,000 | Correct items/unit; total ₦1,000; change ₦1,000; save once |
| Correct a quantity | Customer changes from 3 bottles to 2 | Finds visible quantity controls without a tutorial; no duplicate item |
| Distinguish units | Customer wants 1 carton, not 1 bottle; carton contains 24 | Chooses the correct unit and can explain the charged quantity |
| Change an agreed price | A permitted negotiated price is lower than the displayed price | Finds Change price; understands per-unit versus line total; records allowed reason |
| Part cash/part credit | ₦35,000 sale, ₦20,000 cash, known customer pays rest later | Chooses explicit credit; identifies ₦15,000 owed; does not mark fully paid |
| Repayment | Existing ₦15,000 debt; customer pays ₦5,000 | Records payment against that customer; owes ₦10,000; no new sale |
| Put sale aside | Buyer leaves to get money; next customer is waiting | Keeps draft, starts another sale, retrieves correct saved sale; no premature payment |
| Offline operation | Disconnect while the sale is open | Continues supported local work; can distinguish saved here from copied online |
| Unconfirmed transfer | Show saved transfer not confirmed by bank/provider | Participant states that arrival of money is not confirmed; does not read it as verified Paid |
| Printer failure | Receipt did not print after save | Chooses reprint/help; does not create another sale |
| Close cash | Cash expected ₦45,000; counted ₦44,000 | Understands ₦1,000 less than expected and records it without modifying sales |
| Learnability repeat | Repeat a normal cash sale after other tasks | Completes with less hesitation; layout did not require memorized shortcuts |

No rigid completion-time requirement for unfamiliar complex tasks in the first round. Record times, errors, and help first. Initial cash-sale targets are under 90 seconds unaided on the first attempt and under 45 seconds on repeat, with test conditions recorded; revise targets only with an explanation, never to conceal a failure.

## 4. Safety and comprehension gates

- No participant should be led by the interface into treating an unconfirmed transfer as confirmed bank money. Any such observed misunderstanding is a critical design issue requiring investigation and revision before release.
- No silent short-payment-to-debt conversion or unit reinterpretation is allowed, regardless of average task-success score.
- At least 80% independently complete the normal sale and find the correction controls in the initial round. Report counts and assistance explicitly.
- If participants repeatedly need the same explanation, change the interface or wording. Do not make the workaround a required training script.
- Ask **What do you think happened?** after save, credit, and unconfirmed-transfer states. A correct click without correct understanding is not a full pass.
- First-glance success does not prove accessibility, long-term accuracy, crash recovery, or secure payment operation. Verify those separately.

## 5. Designer craft review

Inspect actual renders independently from usability scores:

- Clear hierarchy: item identity → sale contents → total → next action.
- Refined shelf/receipt balance at wide and compact sizes; no empty decorative regions.
- Consistent baselines, column alignment, text weights, spacing, icon stroke, and surface treatment.
- One dominant action per task stage; natural product imagery does not drown the typography.
- No clipped currency, accidental wrapping, inconsistent decimals, or ambiguous unit/price relationship.
- No photo, long names, large prices, disabled states, and errors look intentionally designed.
- Dark ink and restrained teal remain coherent; accessibility contrast passes without washed-out fine print.
- Quiet transitions support orientation and finish within the defined motion budget.
- Review normal, hover, focus, pressed, unavailable, pending, failed, and restored states—not just a pristine screenshot.

Professional visual review must not trade larger readable controls for a more fashionable but harder-to-use screenshot. Designer praise is useful feedback, not a measurable guarantee of universal acclaim.

## 6. Result record

For each session record:

```text
Build / guide version:
Screen size / Windows scaling / input hardware:
Participant code / role / prior experience / UI language comfort:
Task:
First attempted action:
Completed correctly: yes / no / with help
Elapsed time:
Understanding of resulting amount and status:
Errors or hesitations:
Help provided, exact wording:
Participant's preferred wording, if any:
Severity: critical / major / minor / preference
Proposed change and reason:
Retest result:
```

Report raw task counts, recurring problems, critical incidents, visual findings, changes made, and remaining uncertainty. Avoid invented satisfaction scores or percentages without participant denominators.
