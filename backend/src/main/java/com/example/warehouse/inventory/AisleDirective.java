package com.example.warehouse.inventory;

import com.example.warehouse.ApiModels;
import com.example.warehouse.routing.RoutePlanner;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Extracts an aisle instruction from an operator's free-text putaway prompt.
 *
 * <p>Pure and side-effect free so it can be tested directly, following the same
 * pattern as {@link RoutePlanner}'s geometry helpers and the frontend's
 * {@code narrative.ts}.
 *
 * <p>Why this exists rather than leaving it to the model: with
 * {@code AI_PROVIDER=mock} -- the deterministic default the demo runs on --
 * {@code PlacementAdvisor} never reads the prompt at all, it just takes candidates
 * in order. Even under OpenRouter, an instruction the caller can verify is better
 * enforced by filtering the candidate list than by asking the model to behave. So
 * the directive is parsed here and applied as a filter, and the model only ever
 * sees slots it is allowed to choose.
 */
public final class AisleDirective {
  /** Requires the word "aisle" adjacent to the identifier, so a stray "B" in prose
   * such as "batch B arrived" is not read as a placement instruction. Accepts
   * either order ("aisle B", "B aisle") and an optional separator.
   *
   * <p>The identifier is a single letter or one-to-two digits, never a word. A
   * looser token matched the ordinary English around the instruction rather than
   * the instruction itself: "in aisle B" yielded "in" and "use aisle B" yielded
   * "use", because the reversed branch found them earlier in the string. */
  private static final Pattern PATTERN = Pattern.compile(
      "\\baisle\\s*[:#-]?\\s*([a-z]|[0-9]{1,2})\\b|\\b([a-z]|[0-9]{1,2})\\s+aisle\\b",
      Pattern.CASE_INSENSITIVE);

  private AisleDirective() {}

  /** The aisle the operator asked for, if any.
   *
   * <p>Returns empty when no aisle is mentioned, which means "no constraint" rather
   * than "no match" -- the caller then considers every eligible slot. A mentioned
   * aisle that does not exist is deliberately *not* empty: it comes back as the raw
   * token so the caller can reject it, because silently ignoring "aisle Z" would
   * place the pallet somewhere the operator did not ask for. */
  public static Optional<String> parse(String prompt) {
    if (prompt == null || prompt.isBlank()) return Optional.empty();
    Matcher matcher = PATTERN.matcher(prompt);
    if (!matcher.find()) return Optional.empty();
    String token = matcher.group(1) != null ? matcher.group(1) : matcher.group(2);
    return Optional.of(token.toUpperCase(Locale.ROOT));
  }

  /** Applies the directive to the candidate list.
   *
   * <p>An aisle with nothing free is an error, not a fallback. The operator named a
   * place; quietly using a different one would hide the constraint at exactly the
   * moment it mattered. The caller turns this into an observable PUTAWAY_REJECTED. */
  public static List<ApiModels.CandidateSlot> restrict(List<ApiModels.CandidateSlot> candidates, String prompt,
      List<ApiModels.AisleView> aisles) {
    Optional<String> requested = parse(prompt);
    if (requested.isEmpty()) return candidates;
    String aisleId = requested.get();

    boolean known = aisles.stream().anyMatch(aisle -> aisle.id().equalsIgnoreCase(aisleId));
    if (!known) throw new IllegalArgumentException(
        "Unknown aisle '%s'. This warehouse has %s".formatted(aisleId, describe(aisles)));

    List<ApiModels.CandidateSlot> restricted = candidates.stream()
        .filter(slot -> aisleId.equalsIgnoreCase(slot.aisleId()))
        .toList();
    if (restricted.isEmpty()) throw new IllegalArgumentException("Aisle %s has no free slots".formatted(aisleId));
    return restricted;
  }

  private static String describe(List<ApiModels.AisleView> aisles) {
    return aisles.isEmpty() ? "no named aisles"
        : aisles.stream().map(ApiModels.AisleView::id).collect(java.util.stream.Collectors.joining(", "));
  }
}
