import { render, screen, fireEvent } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { expect, it, vi } from "vitest";
import { EventEditor, localDateTime, utcDateTime } from "./EventEditor";
const config = {
  games: ["SPIN"],
  weights: { REGULAR: 40, CONSULTATION: 25, PREMIUM: 20, GIFT: 10, GRAND: 5 },
  validityDays: 90,
  lowStockThreshold: 3,
  publicUrl: "http://localhost:3000",
  notifyEmail: "staff@example.test",
  termsVersion: "v1",
  terms: "One official attempt.",
  questions: [],
};
function show(active = false, stock = true, changed = false) {
  const draft = {
    id: "e1",
    name: "Business gathering",
    slug: "gathering",
    description: "Event description",
    starts_at: "2030-06-01T09:00:00Z",
    ends_at: "2030-06-02T18:00:00Z",
    approved: true,
    active,
    config,
  };
  const selected = {...draft};
  if(changed)draft.name='Updated unsaved name';
  const handlers = {
    onChange: vi.fn(),
    onNew: vi.fn(),
    onLoad: vi.fn(),
    onSave: vi.fn(),
    onToggle: vi.fn(),
  };
  render(
    <EventEditor
      draft={draft}
      selectedEvent={selected}
      prizes={stock ? [{ active: true, approved: true, remaining: 3 }] : []}
      busy={false}
      {...handlers}
    />,
  );
  return handlers;
}
it("uses labelled fields and date/time pickers with no raw configuration editor", () => {
  show();
  expect(screen.getByLabelText("Event name")).toHaveValue("Business gathering");
  expect(screen.getByLabelText("Start date and time")).toHaveAttribute(
    "type",
    "datetime-local",
  );
  expect(screen.getByLabelText("End date and time")).toHaveAttribute(
    "type",
    "datetime-local",
  );
  expect(screen.queryByText(/Rules JSON/)).not.toBeInTheDocument();
  expect(screen.getByLabelText("Voucher validity (days)")).toHaveValue(90);
});
it("offers explicit activation when approved rules and stock are ready", async () => {
  const user = userEvent.setup();
  const handlers = show();
  const activate = screen.getByRole("button", { name: "Activate event" });
  expect(activate).toBeEnabled();
  await user.click(activate);
  expect(handlers.onToggle).toHaveBeenCalledOnce();
});
it("explains unavailable stock and prevents activation without approved inventory", () => {
  show(false, false);
  expect(screen.getByRole("button", { name: "Activate event" })).toBeDisabled();
  expect(
    screen.getByText(/Approved prizes with available stock/),
  ).toBeInTheDocument();
});
it("locks settings while active but provides a working deactivate action", async () => {
  const user = userEvent.setup();
  const handlers = show(true);
  expect(screen.getByLabelText("Event name")).toBeDisabled();
  expect(
    screen.getByRole("button", { name: "Save event settings" }),
  ).toBeDisabled();
  await user.click(screen.getByRole("button", { name: "Deactivate event" }));
  expect(handlers.onToggle).toHaveBeenCalledOnce();
});
it("date controls convert local selections back into UTC for the server", () => {
  const handlers = show();
  const input = screen.getByLabelText("Start date and time");
  fireEvent.change(input, { target: { value: "2030-06-01T11:30" } });
  expect(handlers.onChange).toHaveBeenCalledWith(
    expect.objectContaining({ starts_at: utcDateTime("2030-06-01T11:30") }),
  );
  expect(utcDateTime(localDateTime("2030-06-01T09:00:00.000Z"))).toBe(
    "2030-06-01T09:00:00.000Z",
  );
});

it('requires changed settings to be saved before activating',()=>{show(false,true,true);expect(screen.getByRole('button',{name:'Activate event'})).toBeDisabled();expect(screen.getByText('Save your changes below before activating this event.')).toBeInTheDocument();});
