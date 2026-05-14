import { CheckCircle2 } from "lucide-react";
import { PageHeader } from "@/components/ui/PageHeader";
import { DataGrid, type Column } from "@/components/ui/DataGrid";
import { StatusPill } from "@/components/ui/StatusPill";
import { useCurrentUser } from "@/lib/UserContext";

interface Persona {
  username: string;
  fullName: string;
  role: string;
  description: string;
}

const PERSONAS: Persona[] = [
  { username: "emma",           fullName: "Emma Catalog",        role: "catalog_manager",       description: "Maintains the product catalogue — pricing, reorder policy, make-vs-buy, discontinue." },
  { username: "sarah",          fullName: "Sarah Sales",         role: "sales_clerk",           description: "Registers customers and places sales orders." },
  { username: "sales-mgr",      fullName: "Sales Manager",       role: "sales_manager",         description: "Cancels sales orders; the only role permitted to cancel." },
  { username: "mike",           fullName: "Mike Warehouse",      role: "warehouse_clerk",       description: "Posts shipments and goods receipts; drives stock movements." },
  { username: "warehouse-mgr",  fullName: "Warehouse Manager",   role: "warehouse_manager",     description: "Adjusts stock balances; oversees warehouse operations." },
  { username: "linda",          fullName: "Linda Planner",       role: "production_planner",    description: "Plans work order priorities; authors BOM drafts." },
  { username: "production-sup", fullName: "Production Supervisor", role: "production_supervisor", description: "Completes work order operations and signs off completion." },
  { username: "tom",            fullName: "Tom Purchasing",      role: "purchasing_clerk",      description: "Creates purchase requisitions; processes receipts." },
  { username: "purchasing-mgr", fullName: "Purchasing Manager",  role: "purchasing_manager",    description: "Approves purchase orders; authors supplier prices." },
  { username: "olivia",         fullName: "Olivia Accountant",   role: "accountant",            description: "Records supplier invoices; processes AP and AR payments." },
  { username: "daniel",         fullName: "Daniel Finance",      role: "finance_manager",       description: "Approves manual-review invoices; reverses journal entries." },
  { username: "auditor",        fullName: "Auditor",             role: "auditor",               description: "Read-only access to audit logs and journals." },
  { username: "sysadmin",       fullName: "Sysadmin",            role: "sysadmin",              description: "System administration; persona switcher access." },
];

/**
 * Read-only listing of the 13 seeded demo personas + their primary role
 * + the current-user highlight. Source-of-truth is Keycloak's realm import
 * (db/keycloak/northwood-realm.json); this page mirrors that list so
 * operators can see who's in the system without hitting Keycloak admin.
 */
export function Users() {
  const { me } = useCurrentUser();

  const columns: Column<Persona>[] = [
    {
      key: "username",
      header: "Username",
      width: "160px",
      render: (p) => (
        <span className="flex items-center gap-1.5 font-mono text-xs">
          {p.username}
          {me?.username === p.username && (
            <CheckCircle2 className="h-3.5 w-3.5 text-status-success" aria-label="current user" />
          )}
        </span>
      ),
    },
    {
      key: "fullName",
      header: "Name",
      width: "200px",
      render: (p) => <span className={me?.username === p.username ? "font-semibold" : ""}>{p.fullName}</span>,
    },
    {
      key: "role",
      header: "Role",
      width: "200px",
      render: (p) => <StatusPill label={p.role} tone="neutral" />,
    },
    {
      key: "description",
      header: "Responsibility",
      render: (p) => <span className="text-text-muted">{p.description}</span>,
    },
  ];

  return (
    <>
      <PageHeader
        title="Users"
        description="The 13 seeded demo personas. Each maps to a single Keycloak realm role; the persona switcher (top-right) flips between them. To add a real user, edit db/keycloak/northwood-realm.json and reload the realm."
        trail={[
          { label: "Home", to: "/" },
          { label: "System" },
          { label: "Users" },
        ]}
      />
      <div className="px-8 py-6">
        <DataGrid
          columns={columns}
          rows={PERSONAS}
          rowKey={(p) => p.username}
          emptyState="No personas configured."
        />
        <div className="mt-3 text-xs text-text-muted">
          {PERSONAS.length} personas. Authenticated as <strong>{me?.username ?? "—"}</strong>.
        </div>
      </div>
    </>
  );
}
