package com.example.warehouse.scenario;

import com.example.warehouse.ApiModels;
import java.util.List;

/** Canonical demo presets exposed through the existing public projection. */
public final class ScenarioPreset {
  private static final List<ApiModels.ScenarioPreset> PRESETS = List.of(
      new ApiModels.ScenarioPreset("balanced-shift", "Balanced shift",
          "Inbound and outbound work sharing one vehicle.", 32, 4, "MIXED", 4, "NORMAL", 82),
      new ApiModels.ScenarioPreset("inbound-surge", "Inbound surge",
          "Clear a busy staging lane with auto-planned put-away.", 24, 12, "PUTAWAY", 6, "HIGH", 78),
      new ApiModels.ScenarioPreset("outbound-wave", "Outbound wave",
          "Fulfil a priority shipment from storage to outbound.", 40, 0, "OUTBOUND", 6, "URGENT", 90));

  private ScenarioPreset() {}

  public static List<ApiModels.ScenarioPreset> all() {
    return PRESETS;
  }
}
