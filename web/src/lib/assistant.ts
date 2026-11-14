import type { AiLook, AiMessage } from "./types";

/*
 * The assistant's conversations: kept in this tab only (sessionStorage), per account, so closing
 * the tab, signing out or turning the assistant off lets them go. Earlier versions kept them in
 * localStorage; forgetting clears those too.
 */

export interface Conversation {
  messages: AiMessage[];
  /** What it looked at, by the index of the reply it goes with. */
  looks: Record<number, AiLook[]>;
}

const PREFIX = "samh.assistant.";
const keyFor = (userId: string) => `${PREFIX}${userId}`;

export function loadConversation(userId: string): Conversation {
  try {
    const raw = sessionStorage.getItem(keyFor(userId));
    if (raw) return JSON.parse(raw) as Conversation;
  } catch { /* private window or blocked storage: start fresh */ }
  return { messages: [], looks: {} };
}

export function saveConversation(userId: string, conversation: Conversation): void {
  try {
    if (conversation.messages.length === 0) sessionStorage.removeItem(keyFor(userId));
    else sessionStorage.setItem(keyFor(userId), JSON.stringify(conversation));
  } catch { /* kept on the page only */ }
}

/** Forgets every conversation in this browser: on signing out, and when the assistant is turned off. */
export function forgetConversations(): void {
  for (const store of [() => sessionStorage, () => localStorage]) {
    try {
      const s = store();
      const keys: string[] = [];
      for (let i = 0; i < s.length; i++) {
        const k = s.key(i);
        if (k?.startsWith(PREFIX)) keys.push(k);
      }
      keys.forEach((k) => s.removeItem(k));
    } catch { /* nothing kept there */ }
  }
}
