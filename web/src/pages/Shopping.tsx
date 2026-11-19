import { useEffect, useState } from "react";
import { Barcode, Columns2, ListChecks, Plus, ScanSearch, Search, Trash2, X } from "lucide-react";
import { api } from "../lib/api";
import { useFoodNumbers } from "../lib/fields";
import { boughtLabel, compareLines, keyNutrients, shoppingOrder } from "../lib/shopping";
import { layoutKey, type Food, type ShoppingList } from "../lib/types";
import { useArrange, type Section } from "../components/Arrange";
import { Button, Card, ErrorNotice, IconButton, PageHeader, Spinner, errorToast, toast, useLoad } from "../components/ui";
import { GradeBadge, GradeReasons } from "./FoodGrade";

/*
 * Shopping (from Food): look a product up by name or barcode and see its grade for the person's
 * goals, why, and what a serving holds; put two side by side; and keep a shopping list. The
 * person's own: never shared. Its sections are arranged under "web.shopping" (ids saved).
 */

export function Shopping() {
  const numbers = useFoodNumbers();
  const list = useLoad(() => api.shopping(), []);
  const [product, setProduct] = useState<Food | null>(null);
  const [compare, setCompare] = useState<(Food | null)[]>([null, null]);
  const toList = async (food: Food) => {
    try { list.setData(await api.addShopping({ food })); toast(`Added ${food.name} to your list`); } catch (e) { errorToast(e); }
  };
  const toCompare = (food: Food) => {
    setCompare(([a, b]) => (!a ? [food, b] : !b ? [a, food] : [a, food]));
    toast(`${food.name} is ready to compare`);
  };
  const sections: Section[] = [
    { id: "find", label: "Look up a product", node: <FindCard numbers={numbers} product={product} onProduct={setProduct} onList={toList} onCompare={toCompare} /> },
    { id: "compare", label: "Compare two", node: <CompareCard numbers={numbers} pair={compare} onClear={(i) => setCompare((p) => p.map((f, j) => (j === i ? null : f)))} /> },
    { id: "list", label: "Shopping list", node: <ListCard list={list.data} error={list.error} onRetry={list.reload} onChange={list.setData} /> },
  ];
  const arranged = useArrange(layoutKey("shopping"), "Shopping", sections);
  return (
    <div className="page">
      <PageHeader title="Shopping" subtitle="See how a food fits your goals before you buy" actions={arranged.button} />
      {arranged.render()}
    </div>
  );
}

/** Search by name, or type a barcode: each result with its grade; one opens with its reasons and a serving's nutrients. */
function FindCard({ numbers, product, onProduct, onList, onCompare }: {
  numbers: boolean; product: Food | null; onProduct: (f: Food | null) => void; onList: (f: Food) => void; onCompare: (f: Food) => void;
}) {
  const [query, setQuery] = useState("");
  const [results, setResults] = useState<Food[] | null>(null);
  const [busy, setBusy] = useState(false);
  const isBarcode = /^\d{8,14}$/.test(query.trim());
  useEffect(() => {
    const q = query.trim();
    if (q.length < 2 || isBarcode) { setResults(null); setBusy(false); return; }
    let cancelled = false;
    setBusy(true);
    const timer = setTimeout(() => {
      api.searchFoods(q).then((r) => { if (!cancelled) setResults([...r.mine, ...r.online]); }, (e) => { if (!cancelled) errorToast(e); })
        .finally(() => { if (!cancelled) setBusy(false); });
    }, 350);
    return () => { cancelled = true; clearTimeout(timer); };
  }, [query, isBarcode]);
  const lookUp = async () => {
    setBusy(true);
    try { onProduct(await api.barcode(query.trim())); } catch (e) { errorToast(e); }
    setBusy(false);
  };
  return (
    <Card id="find" title="Look up a product" icon={<ScanSearch size={18} />} className="span-2">
      <form className="search-box" onSubmit={(e) => { e.preventDefault(); if (isBarcode) void lookUp(); }}>
        {isBarcode ? <Barcode size={20} /> : <Search size={20} />}
        <input value={query} onChange={(e) => setQuery(e.target.value)} placeholder="A food's name, or its barcode" aria-label="Look up a product" />
        {busy && <Spinner size={18} />}
      </form>
      {isBarcode && <Button variant="primary" onClick={lookUp} busy={busy}><Barcode size={18} /> Look up barcode {query.trim()}</Button>}
      {product && <Product food={product} numbers={numbers} onList={onList} onCompare={onCompare} onClose={() => onProduct(null)} />}
      {!product && results && (
        <div className="search-results">
          {results.map((f, i) => (
            <button key={`${f.id ?? f.barcode ?? f.name}-${i}`} className="row" onClick={() => onProduct(f)}>
              <div className="row-main">
                <div className="row-title">{f.name}</div>
                <div className="row-sub">{[f.brand, f.serving].filter(Boolean).join(" · ")}</div>
              </div>
              <GradeBadge grade={f.grade} />
            </button>
          ))}
          {results.length === 0 && <p className="muted">Nothing found. Try other words, or the barcode.</p>}
        </div>
      )}
    </Card>
  );
}

/** One product: its grade and why, what a serving holds (while amounts of food show), and what to do with it. */
function Product({ food, numbers, onList, onCompare, onClose }: { food: Food; numbers: boolean; onList: (f: Food) => void; onCompare: (f: Food) => void; onClose: () => void }) {
  return (
    <div className="product">
      <div className="product-head">
        <div>
          <div className="row-title">{food.name}</div>
          <div className="muted small">{[food.brand, `per ${food.serving}`].filter(Boolean).join(" · ")}</div>
        </div>
        <IconButton label="Close" onClick={onClose}><X size={18} /></IconButton>
      </div>
      <GradeReasons grade={food.grade} />
      {numbers && (
        <dl className="nutrient-list">
          {keyNutrients(food).map((l) => <div key={l.key}><dt>{l.label}</dt><dd>{l.value}</dd></div>)}
        </dl>
      )}
      <div className="button-row">
        <Button variant="primary" onClick={() => onList(food)}><Plus size={16} /> Add to list</Button>
        <Button onClick={() => onCompare(food)}><Columns2 size={16} /> Compare</Button>
      </div>
    </div>
  );
}

/** Two products side by side: grades, servings and (while they show) what a serving holds. */
function CompareCard({ numbers, pair, onClear }: { numbers: boolean; pair: (Food | null)[]; onClear: (i: number) => void }) {
  const [a, b] = pair;
  return (
    <Card id="compare" title="Compare two" icon={<Columns2 size={18} />} className="span-2">
      {!a && !b && <p className="muted small">Look up a product and choose Compare, then another.</p>}
      {(a || b) && (
        <table className="food-compare">
          <thead>
            <tr>
              <th scope="col"><span className="sr-only">Nutrient</span></th>
              {[a, b].map((f, i) => (
                <th key={i} scope="col">
                  {f ? <span className="food-compare-name"><span>{f.name}</span><IconButton label={`Take ${f.name} out`} onClick={() => onClear(i)}><X size={14} /></IconButton></span> : <span className="muted">Choose another</span>}
                </th>
              ))}
            </tr>
          </thead>
          {a && b && (
            <tbody>
              {compareLines(a, b, numbers).map((l) => (
                <tr key={l.label}>
                  <th scope="row">{l.label}</th>
                  <td>{l.label === "Grade" && a.grade ? <GradeBadge grade={a.grade} /> : l.a}</td>
                  <td>{l.label === "Grade" && b.grade ? <GradeBadge grade={b.grade} /> : l.b}</td>
                </tr>
              ))}
            </tbody>
          )}
        </table>
      )}
    </Card>
  );
}

/** The shopping list: add by name, tick off, clear what's bought. */
function ListCard({ list, error, onRetry, onChange }: { list?: ShoppingList; error: string | null; onRetry: () => void; onChange: (l: ShoppingList) => void }) {
  const [name, setName] = useState("");
  const run = async (work: () => Promise<ShoppingList>) => {
    try { onChange(await work()); } catch (e) { errorToast(e); }
  };
  const items = shoppingOrder(list?.items ?? []);
  const clear = boughtLabel(items);
  return (
    <Card id="list" title="Shopping list" icon={<ListChecks size={18} />}>
      {error && <ErrorNotice message={error} onRetry={onRetry} />}
      <form className="inline-add" onSubmit={(e) => { e.preventDefault(); const n = name.trim(); if (n) { setName(""); void run(() => api.addShopping({ name: n })); } }}>
        <input value={name} onChange={(e) => setName(e.target.value)} placeholder="Add something" aria-label="Add to the shopping list" />
        <Button type="submit"><Plus size={16} /> Add</Button>
      </form>
      {list && items.length === 0 && <p className="muted small">Nothing on the list yet.</p>}
      {items.length > 0 && (
        <ul className="rows">
          {items.map((i) => (
            <li key={i.id} className={`row shopping-item ${i.checked ? "is-bought" : ""}`}>
              <label className="check-row row-main">
                <input type="checkbox" checked={i.checked} onChange={() => void run(() => api.tickShopping(i.id, !i.checked))} />
                <span><span className="row-title">{i.name}</span>{i.brand && <span className="row-sub"> · {i.brand}</span>}</span>
              </label>
              <GradeBadge grade={i.food?.grade} />
              <IconButton label={`Remove ${i.name}`} onClick={() => void run(() => api.removeShopping(i.id))}><Trash2 size={16} /></IconButton>
            </li>
          ))}
        </ul>
      )}
      {clear && <Button variant="ghost" onClick={() => void run(() => api.clearBought())}>{clear}</Button>}
    </Card>
  );
}
