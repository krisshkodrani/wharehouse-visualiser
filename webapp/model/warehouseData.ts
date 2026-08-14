import type { WarehouseModelData } from "./types";

export const warehouseModelData: WarehouseModelData = {
  selectedWarehouseId: "linz",
  selectedRackId: null,
  selectedRackName: "",
  warehouses: [
    {
      id: "linz",
      name: "Linz Central Warehouse",
      location: "Industriezeile 44, Linz",
      signText: "LINZ · CENTRAL",
      floorColor: "#dce8e5",
      accentColor: "#0a6ed1",
      inventory: [
        { item: "Drive assemblies", quantity: 128 },
        { item: "Sensor modules", quantity: 346 },
        { item: "Packing crates", quantity: 92 },
        { item: "Safety equipment", quantity: 57 }
      ],
      racks: [
        { id: "L-A1", name: "Rack L-A1", position: [-7, 0, -5.7], bays: 4, emptySlots: [[1, 1], [3, 2]] },
        { id: "L-A2", name: "Rack L-A2", position: [-1.8, 0, -5.7], bays: 4, emptySlots: [[0, 2], [2, 0]] },
        { id: "L-A3", name: "Rack L-A3", position: [3.4, 0, -5.7], bays: 3, emptySlots: [[1, 0]] },
        { id: "L-B1", name: "Rack L-B1", position: [-7, 0, 1.8], bays: 4, emptySlots: [[0, 1], [2, 2]] },
        { id: "L-B2", name: "Rack L-B2", position: [-1.8, 0, 1.8], bays: 4, emptySlots: [[1, 2], [3, 0]] },
        { id: "L-B3", name: "Rack L-B3", position: [3.4, 0, 1.8], bays: 3, emptySlots: [[2, 1]] },
        { id: "L-D1", name: "Dispatch Rack L-D1", position: [8.2, 0, -6.9], bays: 2, emptySlots: [[1, 1], [0, 2]] }
      ],
      forkliftStops: [[8.2, 0, -4.7], [8.2, 0, 5.5]]
    },
    {
      id: "vienna",
      name: "Vienna Logistics Hub",
      location: "Freudenauer Hafenstraße 18, Vienna",
      signText: "WIEN · LOGISTICS HUB",
      floorColor: "#e8e1d6",
      accentColor: "#b85c00",
      inventory: [
        { item: "Control panels", quantity: 214 },
        { item: "Hydraulic kits", quantity: 73 },
        { item: "Export pallets", quantity: 161 },
        { item: "Maintenance parts", quantity: 408 }
      ],
      racks: [
        { id: "V-N1", name: "Rack V-N1", position: [-7, 0, -5.5], rotationY: 0.08, bays: 4, emptySlots: [[0, 2], [2, 1]] },
        { id: "V-N2", name: "Rack V-N2", position: [-1.8, 0, -5.5], rotationY: 0.08, bays: 4, emptySlots: [[1, 0], [3, 2]] },
        { id: "V-S1", name: "Rack V-S1", position: [-7, 0, 2], rotationY: -0.08, bays: 4, emptySlots: [[1, 2], [3, 0]] },
        { id: "V-S2", name: "Rack V-S2", position: [-1.8, 0, 2], rotationY: -0.08, bays: 4, emptySlots: [[0, 1], [2, 2]] },
        { id: "V-E1", name: "Rack V-E1", position: [3.4, 0, -0.8], rotationY: Math.PI / 2, bays: 4, emptySlots: [[1, 1], [3, 2]] },
        { id: "V-D1", name: "Dispatch Rack V-D1", position: [8.4, 0, -6.9], bays: 2, emptySlots: [[1, 1], [0, 2]] }
      ],
      forkliftStops: [[8.4, 0, -4.7], [8.4, 0, 5.5]]
    }
  ]
};
