import { useState } from "react";
import { ClipboardList, Lock } from "lucide-react";
import { NAMES } from "../lib/nav";
import { SENSITIVE } from "../lib/types";
import { useViewing } from "../lib/viewing";
import { ArrangeButton } from "../components/Arrange";
import { FeatureArrange, useFeatureItems, useFeaturesShown } from "../components/Navigation";
import { Card, Empty, MenuGroup, MenuRow, PageHeader } from "../components/ui";

/*
 * Health: every feature the person uses, one tap each, in their order (Layouts.NAV, shared with the
 * sidebar and the phone app's Health tab), then "Choose what you track". While viewing someone's
 * share, the features they shared.
 */
export function HealthPage() {
  const viewing = useViewing((s) => s.share);
  const items = useFeatureItems();
  const shown = useFeaturesShown(items);
  const [arranging, setArranging] = useState(false);
  const hidden = items.length - shown.length;
  const sensitive = shown.some((f) => SENSITIVE.includes(f.module));
  return (
    <div className="page menu-page">
      <PageHeader title={NAMES.health} subtitle={viewing ? `What ${viewing.ownerName} shares with you` : "Everything you've turned on"}
        actions={!viewing && items.length > 0 ? <ArrangeButton title="your features" onClick={() => setArranging(true)} /> : null} />
      {shown.length > 0 ? (
        <MenuGroup title={viewing ? undefined : "Your features"}>
          {shown.map((f) => <MenuRow key={f.id} to={f.to} icon={<f.icon size={20} />} title={f.label} sub={f.about} />)}
        </MenuGroup>
      ) : (
        <Card>
          <Empty icon={<ClipboardList />} title={items.length > 0 ? "Everything here is hidden" : "Nothing turned on yet"}>
            {items.length > 0 ? "Arrange, at the top, shows them again." : "Choose what you track, and the features that help with it show here."}
          </Empty>
        </Card>
      )}
      {!viewing && hidden > 0 && shown.length > 0 && (
        <p className="menu-hint">{hidden === 1 ? "One feature is" : `${hidden} features are`} hidden here. Arrange, at the top, shows {hidden === 1 ? "it" : "them"} again.</p>
      )}
      {!viewing && (
        <MenuGroup>
          <MenuRow to="/conditions" icon={<ClipboardList size={20} />} title={NAMES.chooseTracking} sub="Tell the app what you live with and what you're aiming for, and it shows what fits" />
        </MenuGroup>
      )}
      {!viewing && sensitive && (
        <p className="menu-hint"><Lock size={14} aria-hidden="true" /> Private features are encrypted on your server, and AI connector links only see them if you allow it.</p>
      )}
      {arranging && <FeatureArrange items={items} onClose={() => setArranging(false)} />}
    </div>
  );
}
