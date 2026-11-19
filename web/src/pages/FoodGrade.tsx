import type { ReactNode } from "react";
import { Trash2 } from "lucide-react";
import { api } from "../lib/api";
import { gradeClass } from "../lib/shopping";
import type { Food, FoodGrade } from "../lib/types";
import { Button, IconButton, Spinner, errorToast, toast, useLoad } from "../components/ui";

/*
 * Food grades (A to F, for the person's own goals) and their own foods, kept apart from the
 * add-food dialog so it changes little. Grades come from the server only while they're on
 * (You › What you track › Food grades), so nothing shows when they're off.
 */

/** A food's grade as a small letter, its reasons read out and on hover. */
export function GradeBadge({ grade }: { grade?: FoodGrade }) {
  if (!grade) return null;
  const why = grade.reasons.join(", ");
  return (
    <span className={`grade ${gradeClass(grade.letter)}`} title={why} role="img" aria-label={`Grade ${grade.letter}${why ? `: ${why}` : ""}`}>
      {grade.letter}
    </span>
  );
}

/** The grade with its reasons in a line, for a food's dialog or a product. */
export function GradeReasons({ grade }: { grade?: FoodGrade }) {
  if (!grade) return null;
  return (
    <div className="grade-line">
      <GradeBadge grade={grade} />
      <div className="grade-why">
        <span className="small">{grade.reasons.join(" · ")}</span>
        <span className="muted small">For your goals</span>
      </div>
    </div>
  );
}

/**
 * The person's foods, most recent first, each with a way to remove it, and "Clear my foods" for
 * all of them (old ones from before search moved to USDA, say). The diary keeps every entry.
 */
export function MyFoods({ row }: { row: (food: Food) => ReactNode }) {
  const { data, loading, error, setData } = useLoad(() => api.recentFoods(), []);
  if (loading && !data) return <Spinner />;
  if (error) return <div className="notice notice-error">{error}</div>;
  if (!data?.length) return <p className="muted">Foods you log show up here, most recent first.</p>;
  const remove = async (food: Food) => {
    try {
      await api.deleteFood(food.id!);
      setData(data.filter((f) => f.id !== food.id));
      toast(`Removed ${food.name}`);
    } catch (e) { errorToast(e); }
  };
  const clear = async () => {
    if (!confirm("Clear my foods? Your diary stays as it is.")) return;
    try {
      await api.clearFoods();
      setData([]);
      toast("Your foods are cleared");
    } catch (e) { errorToast(e); }
  };
  return (
    <>
      <div className="search-results">
        {data.map((f) => (
          <div key={f.id} className="my-food">
            {row(f)}
            <IconButton label={`Remove ${f.name}`} onClick={() => void remove(f)}><Trash2 size={18} /></IconButton>
          </div>
        ))}
      </div>
      <Button variant="ghost" onClick={() => void clear()}><Trash2 size={16} /> Clear my foods</Button>
    </>
  );
}
