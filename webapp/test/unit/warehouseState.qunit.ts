import { warehouseModelData } from "../../model/warehouseData";
import { getWarehouse, projectVisualConfig, selectRack, selectWarehouse } from "../../model/warehouseState";

QUnit.module("warehouse state");

QUnit.test("looks up warehouses and projects visual-only state", (assert) => {
  const warehouse = getWarehouse(warehouseModelData, "linz");
  assert.ok(warehouse, "Linz exists");
  const visual = projectVisualConfig(warehouse!);
  assert.strictEqual(visual.id, "linz");
  assert.notOk("inventory" in visual, "inventory is not sent to Babylon");
  assert.notOk("location" in visual, "location is not sent to Babylon");
});

QUnit.test("switching warehouse clears rack selection", (assert) => {
  const data = structuredClone(warehouseModelData);
  data.selectedRackId = "L-A1";
  data.selectedRackName = "Rack L-A1";
  assert.ok(selectWarehouse(data, "vienna"));
  assert.strictEqual(data.selectedRackId, null);
  assert.strictEqual(data.selectedRackName, "");
});

QUnit.test("invalid warehouse and rack IDs do not mutate state", (assert) => {
  const data = structuredClone(warehouseModelData);
  assert.notOk(selectWarehouse(data, "missing"));
  assert.strictEqual(data.selectedWarehouseId, "linz");
  assert.notOk(selectRack(data, "V-N1"), "rack from inactive warehouse is rejected");
  assert.strictEqual(data.selectedRackId, null);
});

QUnit.test("valid rack selection records its ID and display name", (assert) => {
  const data = structuredClone(warehouseModelData);
  assert.ok(selectRack(data, "L-B2"));
  assert.strictEqual(data.selectedRackId, "L-B2");
  assert.strictEqual(data.selectedRackName, "Rack L-B2");
});
