package com.example.warehouse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

class AisleDirectiveTest {

  private static final List<ApiModels.AisleView> AISLES = List.of(
      new ApiModels.AisleView("A", "Aisle A", -5, -6, 0, 26, 3),
      new ApiModels.AisleView("B", "Aisle B", -5, 2, 0, 26, 3),
      new ApiModels.AisleView("C", "Aisle C", -5, 10, 0, 26, 3));

  private static ApiModels.CandidateSlot slot(String id, String aisleId) {
    return new ApiModels.CandidateSlot(id, "Rack " + aisleId + " / Bay 1 / Level 1", 1, 0, 0,
        aisleId, aisleId == null ? null : "Aisle " + aisleId);
  }

  private static final List<ApiModels.CandidateSlot> CANDIDATES = List.of(
      slot("S-A-1", "A"), slot("S-B-1", "B"), slot("S-B-2", "B"), slot("S-C-1", "C"));

  @Test void findsTheAisleInAPlainInstruction() {
    assertThat(AisleDirective.parse("Store this pallet in aisle B.")).contains("B");
  }

  @Test void isCaseInsensitive() {
    assertThat(AisleDirective.parse("put it in Aisle c please")).contains("C");
  }

  @Test void acceptsTheReversedPhrasing() {
    assertThat(AisleDirective.parse("use the B aisle")).contains("B");
  }

  @Test void acceptsASeparator() {
    assertThat(AisleDirective.parse("destination aisle: A")).contains("A");
  }

  @Test void treatsNoMentionAsNoConstraint() {
    assertThat(AisleDirective.parse("Store this pallet in the nearest eligible slot.")).isEmpty();
    assertThat(AisleDirective.parse(null)).isEmpty();
    assertThat(AisleDirective.parse("   ")).isEmpty();
  }

  /** A bare letter must not be read as a placement instruction, or prose like
   * "batch B arrived damaged" would silently constrain the whole putaway. */
  @Test void ignoresALetterThatIsNotNextToTheWordAisle() {
    assertThat(AisleDirective.parse("batch B arrived damaged")).isEmpty();
  }

  @Test void narrowsTheCandidatesToTheNamedAisle() {
    assertThat(AisleDirective.restrict(CANDIDATES, "please use aisle B", AISLES))
        .extracting(ApiModels.CandidateSlot::id)
        .containsExactly("S-B-1", "S-B-2");
  }

  @Test void leavesEveryCandidateWhenNoAisleIsNamed() {
    assertThat(AisleDirective.restrict(CANDIDATES, "nearest eligible slot", AISLES)).isEqualTo(CANDIDATES);
  }

  /** The operator named a place. Placing the pallet somewhere else would hide the
   * constraint at the one moment it mattered, so this is a rejection. */
  @Test void rejectsAnAisleThatHasNothingFree() {
    List<ApiModels.CandidateSlot> withoutB = List.of(slot("S-A-1", "A"), slot("S-C-1", "C"));
    assertThatThrownBy(() -> AisleDirective.restrict(withoutB, "aisle B", AISLES))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Aisle B has no free slots");
  }

  @Test void rejectsAnAisleThatDoesNotExist() {
    assertThatThrownBy(() -> AisleDirective.restrict(CANDIDATES, "aisle Z", AISLES))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Unknown aisle 'Z'")
        .hasMessageContaining("A, B, C");
  }

  /** Slots in racks with no aisle stay reachable when nothing is named, but never
   * satisfy a named aisle. */
  @Test void neverMatchesASlotWithNoAisle() {
    List<ApiModels.CandidateSlot> orphan = List.of(slot("S-X-1", null), slot("S-B-1", "B"));
    assertThat(AisleDirective.restrict(orphan, "aisle B", AISLES))
        .extracting(ApiModels.CandidateSlot::id).containsExactly("S-B-1");
    assertThat(AisleDirective.restrict(orphan, "anywhere", AISLES)).hasSize(2);
  }
}
