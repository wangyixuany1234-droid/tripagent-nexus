import { useTripFlowStore } from "./trip-flow-store";

const userA = "018f0e31-6ec8-7a59-b871-0242ac120001";
const userB = "018f0e31-6ec8-7a59-b871-0242ac120002";
const tripId = "018f0e31-6ec8-7a59-b871-0242ac120101";

describe("trip flow identity isolation", () => {
  it("clears user-scoped references when the authenticated subject changes", () => {
    useTripFlowStore.getState().setAuthenticatedUser(userA);
    useTripFlowStore.setState({
      activeTripId: tripId,
      recentTripIds: [tripId],
    });

    useTripFlowStore.getState().setAuthenticatedUser(userB);

    const state = useTripFlowStore.getState();
    expect(state.userId).toBe(userB);
    expect(state.draft.values.identity.userId).toBe(userB);
    expect(state.activeTripId).toBeUndefined();
    expect(state.recentTripIds).toEqual([]);
    expect(state.documentTasks).toEqual([]);
    expect(state.planningTasks).toEqual([]);
  });

  it("keeps the current workspace when the same subject is restored", () => {
    useTripFlowStore.getState().setAuthenticatedUser(userA);
    useTripFlowStore.setState({
      activeTripId: tripId,
      recentTripIds: [tripId],
    });

    useTripFlowStore.getState().setAuthenticatedUser(userA);

    expect(useTripFlowStore.getState().activeTripId).toBe(tripId);
    expect(useTripFlowStore.getState().recentTripIds).toEqual([tripId]);
  });
});
