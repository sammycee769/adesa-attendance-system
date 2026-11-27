import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { beforeEach, it, expect, vi } from "vitest";
import { VendorPage } from "./VendorPage";
const state = vi.hoisted(() => ({
  role: "ADMIN",
  request: vi.fn(),
  download: vi.fn(),
}));
vi.mock("../../auth/AuthProvider", () => ({
  useAuth: () => ({ user: { id: "admin-1", role: state.role } }),
}));
vi.mock("../../api/client", () => ({
  apiRequest: state.request,
  apiDownload: state.download,
}));
beforeEach(() => {
  state.role = "ADMIN";
  state.request.mockReset();
  state.request.mockImplementation(async (path: string) => {
    if (path.endsWith("/overview"))
      return {
        total: 0,
        statuses: {},
        applications: [],
        subsidiaries: [{ code: "ENERGY", name: "ADESA Energy" }],
        demo: true,
      };
    if (path.endsWith("/links") || path.endsWith("/notices")) return [];
    return {};
  });
});
it.each(["HR", "EMPLOYEE", "COO", "FINANCE", "PROCUREMENT"])(
  "denies %s without requesting vendor data",
  (role) => {
    state.role = role;
    render(<VendorPage />);
    expect(screen.getByRole("alert")).toHaveTextContent("System Administrator");
    expect(state.request).not.toHaveBeenCalled();
  },
);
it("shows an administrator-only overview and secure link form", async () => {
  const user = userEvent.setup();
  render(<VendorPage />);
  expect(await screen.findByText("Total applications")).toBeInTheDocument();
  await user.click(screen.getByRole("button", { name: "Registration Links" }));
  expect(
    screen.getByRole("button", { name: "Generate registration link" }),
  ).toBeInTheDocument();
  expect(screen.getByLabelText("Subsidiary")).toBeInTheDocument();
  expect(screen.getByLabelText("Link type")).toHaveValue("REUSABLE");
});
it("does not show vendor bank details or document links in overview notices", async () => {
  render(<VendorPage />);
  await screen.findByText("Total applications");
  expect(screen.queryByText("Account number")).not.toBeInTheDocument();
  expect(
    screen.queryByRole("img", { name: /signature/i }),
  ).not.toBeInTheDocument();
});
