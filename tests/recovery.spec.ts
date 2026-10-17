import { test, expect } from "@playwright/test";

async function mockedRecovery(page: import("@playwright/test").Page) {
  await page.addInitScript(() => {
    const files = [
      {
        id: "0000000000000102",
        name: "recovered-0000000000000102.png",
        category: "Images",
        format: "png",
        offset: 258,
        size: 83,
        evidence: "PNG chunk CRCs and end marker checked",
        sha256: "",
        originalPath:"/Photos/holiday.png",
        recoverable:true,
      },
      {
        id: "0000000000000620",
        name: "recovered-0000000000000620.pdf",
        category: "Documents",
        format: "pdf",
        offset: 1568,
        size: 86,
        evidence:
          "PDF header/footer found; readability and later revisions unverified",
        sha256: "",
        originalPath:"/Documents/notes.pdf",
        recoverable:true,
      },
    ];
    const calls: { command: string; args: unknown }[] = [];
    Object.assign(window, {
      isTauri: true,
      recoveryCalls: calls,
      __TAURI_INTERNALS__: {
        transformCallback: () => 1,
        unregisterCallback: () => {},
        invoke: async (command: string, args?: { ids: string[]; deep?:boolean }) => {
          calls.push({ command, args });
          if(command==="recovery_capabilities") return {driveAccess:true,canElevate:true,recoveryOnly:false,drives:[{id:"D",label:"D: NTFS",filesystem:"NTFS"},{id:"E",label:"E: USB exFAT",filesystem:"exFAT"}]};
          if (command === "plugin:event|listen") return 1;
          if (
            command === "plugin:event|unlisten" ||
            command === "open_system_trash" ||
            command === "close_recovery_image"
          )
            return;
          if (command === "scan_recovery_image" || command === "scan_recovery_drive")
            return {
              source: "D:\\Evidence\\card.img",
              sourceBytes: 2367,
              scannedBytes: 2367,
              files,
              rejectedHeaders: 3,
              cancelled: false,
              limited: false,
              elapsedMs: 20,
              method: "ntfs-deleted-records-v1",
            };
          if (command === "recovery_preview")
            return {
              image:
                "data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jRZkAAAAASUVORK5CYII=",
              text: null,
            };
          if (command === "export_recovery_files")
            return {
              directory: "E:\\Sift-recovered-1",
              exported: files
                .filter((file) => args?.ids.includes(file.id))
                .map((candidate) => ({
                  candidate,
                  path: `E:\\Sift-recovered-1\\${candidate.category}\\${candidate.name}`,
                })),
              failed: [],
              cancelled: false,
              manifest: "E:\\Sift-recovered-1\\recovery-manifest.json",
              warning: null,
            };
          throw Error(`Unexpected command ${command}`);
        },
      },
    });
  });
}
test("launcher opens both workspaces, preserves clean selection, and never simulates recovery", async ({
  page,
}) => {
  await page.goto("/");
  await page.screenshot({
    path: "artifacts/sift-workspaces.png",
    fullPage: true,
  });
  await expect(
    page.getByRole("heading", { name: "Make room. Bring something back." }),
  ).toBeVisible();
  await page.getByRole("button", { name: "Clean data", exact: true }).click();
  await page
    .getByRole("checkbox", {
      name: "Select Summer in Portugal.mp4",
      exact: true,
    })
    .check();
  await page.getByRole("button", { name: "Switch workspace" }).click();
  await page.getByRole("button", { name: "Recover data", exact: true }).click();
  await page
    .getByRole("button", { name: "Choose disk image", exact: true })
    .click();
  await expect(page.getByRole("dialog")).toContainText(
    "No recovery results are simulated",
  );
  await page.getByRole("button", { name: "Close recovery dialog" }).click();
  await page.getByRole("button", { name: "Switch workspace" }).click();
  await page.getByRole("button", { name: "Clean data", exact: true }).click();
  await expect(
    page.getByRole("checkbox", {
      name: "Select Summer in Portugal.mp4",
      exact: true,
    }),
  ).toBeChecked();
});
test("native recovery filters, previews, inspects evidence, and exports only reviewed IDs", async ({
  page,
}) => {
  await mockedRecovery(page);
  await page.goto("/");
  await page.getByRole("button", { name: "Recover data", exact: true }).click();
  await expect(page.getByRole("button",{name:"Open system trash"})).toHaveCount(0);
  await page
    .getByRole("button", { name: "Choose disk image", exact: true })
    .click();
  await expect(
    page.getByRole("heading", { name: "2 candidate files" }),
  ).toBeVisible();
  await page.locator(".recovery-file").first().scrollIntoViewIfNeeded();
  await expect(
    page.getByRole("img", {
      name: "Preview of recovered-0000000000000102.png",
    }),
  ).toBeVisible();
  await page
    .getByRole("checkbox", { name: "Select recovered-0000000000000102.png" })
    .check();
  await page.getByRole("button", { name: "Grid view", exact: true }).click();
  await expect(page.locator(".recovery-grid .recovery-file")).toHaveCount(2);
  await page
    .getByRole("combobox", { name: "Recovery category" })
    .selectOption("Documents");
  await expect(page.locator(".recovery-file")).toHaveCount(1);
  await page
    .getByRole("button", { name: "Select all matches", exact: true })
    .click();
  await page
    .getByRole("button", { name: "Inspect recovered-0000000000000620.pdf" })
    .click();
  await expect(page.getByRole("dialog")).toContainText(
    "Original path / deletion status",
  );
  await expect(page.getByRole("dialog")).toContainText(
    "later revisions unverified",
  );
  await page.getByRole("button", { name: "Recover this file only" }).click();
  const dialog = page.getByRole("dialog");
  await expect(dialog).toContainText("1 selected candidates");
  await dialog
    .getByRole("button", { name: "Choose destination & save" })
    .click();
  await expect(
    page.getByRole("heading", { name: "1 verified copy saved" }),
  ).toBeVisible();
  await expect(page.getByText("2 matches")).toHaveCount(0);
  const calls = await page.evaluate(
    () =>
      (
        window as unknown as {
          recoveryCalls: { command: string; args: { ids?: string[] } }[];
        }
      ).recoveryCalls,
  );
  expect(
    calls.find((c) => c.command === "export_recovery_files")?.args.ids,
  ).toEqual(["0000000000000620"]);
  expect(calls.some((c) => c.command === "cleanup_files")).toBe(false);
  await page
    .getByRole("combobox", { name: "Recovery category" })
    .selectOption("All");
  await expect(
    page.getByRole("checkbox", {
      name: "Select recovered-0000000000000102.png",
    }),
  ).toBeChecked();
  await expect(
    page.getByRole("checkbox", {
      name: "Select recovered-0000000000000620.pdf",
    }),
  ).not.toBeChecked();
  await page.screenshot({
    path: "artifacts/sift-recovery-desktop.png",
    fullPage: true,
  });
  await page.setViewportSize({ width: 600, height: 900 });
  expect(
    await page.evaluate(
      () => document.documentElement.scrollWidth <= innerWidth,
    ),
  ).toBe(true);
});
test("recovery review traps keyboard focus and cancellation preserves selected files", async ({
  page,
}) => {
  await mockedRecovery(page);
  await page.goto("/");
  await page.getByRole("button", { name: "Recover data", exact: true }).click();
  await page
    .getByRole("button", { name: "Choose disk image", exact: true })
    .click();
  await page
    .getByRole("button", { name: "Select all matches", exact: true })
    .click();
  await page
    .getByRole("button", { name: "Recover selected", exact: true })
    .click();
  await expect(page.getByRole("dialog")).toContainText("2 selected candidates");
  for (let i = 0; i < 7; i++) {
    await page.keyboard.press("Tab");
    expect(
      await page.evaluate(() =>
        Boolean(document.activeElement?.closest("dialog")),
      ),
    ).toBe(true);
  }
  await page.keyboard.press("Escape");
  await expect(page.getByRole("dialog")).toHaveCount(0);
  await expect(
    page.getByRole("checkbox", {
      name: "Select recovered-0000000000000102.png",
    }),
  ).toBeChecked();
});

test("closing the image clears candidates and releases the native evidence session", async ({
  page,
}) => {
  await mockedRecovery(page);
  await page.goto("/");
  await page.getByRole("button", { name: "Recover data", exact: true }).click();
  await page
    .getByRole("button", { name: "Choose disk image", exact: true })
    .click();
  await page
    .getByRole("button", { name: "Select all matches", exact: true })
    .click();
  await page.getByRole("button", { name: "Close image", exact: true }).click();
  await expect(
    page.getByRole("region", { name: "Recovery candidates" }),
  ).toHaveCount(0);
  await expect(
    page.getByRole("button", { name: "Recover selected", exact: true }),
  ).toHaveCount(0);
  await expect(
    page.getByRole("button", { name: "Choose disk image", exact: true }),
  ).toBeEnabled();
  const calls = await page.evaluate(
    () =>
      (window as unknown as { recoveryCalls: { command: string }[] })
        .recoveryCalls,
  );
  expect(
    calls.filter((c) => c.command === "close_recovery_image"),
  ).toHaveLength(1);
});

test("a cancelled destination and native export error keep review and selection available for retry", async ({
  page,
}) => {
  await mockedRecovery(page);
  await page.goto("/");
  await page.evaluate(() => {
    const internal = (
      window as unknown as {
        __TAURI_INTERNALS__: {
          invoke: (command: string, args?: unknown) => Promise<unknown>;
        };
      }
    ).__TAURI_INTERNALS__;
    const original = internal.invoke;
    let exports = 0;
    internal.invoke = async (command, args) => {
      if (command === "export_recovery_files") {
        exports++;
        if (exports === 1) return null;
        if (exports === 2) throw Error("Destination is read-only.");
      }
      return original(command, args);
    };
  });
  await page.getByRole("button", { name: "Recover data", exact: true }).click();
  await page
    .getByRole("button", { name: "Choose disk image", exact: true })
    .click();
  await page
    .getByRole("button", { name: "Select all matches", exact: true })
    .click();
  await page
    .getByRole("button", { name: "Recover selected", exact: true })
    .click();
  const dialog = page.getByRole("dialog");
  const save = dialog.getByRole("button", {
    name: "Choose destination & save",
  });
  await save.click();
  await expect(save).toBeEnabled();
  await expect(dialog).toBeVisible();
  await save.click();
  await expect(dialog.getByRole("alert")).toContainText(
    "Destination is read-only.",
  );
  await expect(save).toBeEnabled();
  await page.getByRole("button", { name: "Close recovery dialog" }).click();
  await expect(
    page.getByRole("checkbox", {
      name: "Select recovered-0000000000000102.png",
    }),
  ).toBeChecked();
});

test("direct storage selection sends only an enumerated drive ID and the chosen scan method",async({page})=>{
  await mockedRecovery(page);await page.goto("/");await page.getByRole("button",{name:"Recover data",exact:true}).click();
  await page.getByRole("combobox",{name:"Recovery source drive"}).selectOption("E");
  await expect(page.getByRole("combobox",{name:"Recovery scan method"})).toHaveValue("deep");
  await page.getByRole("button",{name:"Deep scan storage",exact:true}).click();
  await expect(page.getByRole("heading",{name:"2 candidate files"})).toBeVisible();
  const calls=await page.evaluate(()=>(window as unknown as {recoveryCalls:{command:string;args:unknown}[]}).recoveryCalls);
  expect(calls.find(c=>c.command==="scan_recovery_drive")?.args).toEqual({id:"E",deep:true});
  expect(calls.some(c=>c.command==="open_system_trash")).toBe(false);
});
