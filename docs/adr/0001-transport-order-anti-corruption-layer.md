# ADR 0001: Separate product orders from VDA orders

Status: accepted.

Business transport orders describe objectives, priority, loads, and lifecycle. VDA orders describe one vehicle's executable nodes, edges, actions, release state, and update number. Treating them as one model would leak device protocol constraints into operator workflows and complicate multi-vehicle evolution. The backend therefore decomposes transport orders into tasks and translates each task at the dispatch boundary. The cost is an explicit mapping and audit table; the benefit is stable product semantics and inspectable protocol execution.
