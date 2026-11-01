"""A made-up Orknux server for scripts/screenshots.sh to photograph.

The pictures go in a public repository, so they must never show anybody's real
chats: this answers only what the app's screens ask, with invented data.

    python scripts/screenshot_server.py [port]
"""
import json
import sys
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

AGENT = "Support responder"
WORKSPACE = {
    "id": "1", "name": "Northwind Support", "transcriptionModelId": "2", "speechModelId": "3",
    "voiceButtonHidden": False,
}
AGENTS = [{"id": "1", "name": AGENT, "enabled": True, "modelId": "1"}]


def session(id, title, at, unread=0, pinned=False, llm=None, mode="AUTO"):
    return {
        "id": id, "workspaceId": "1", "title": title, "pinned": pinned, "modelName": "Flash",
        "createdAt": at, "lastMessageAt": at, "unreadCount": unread, "agentId": "1", "agentName": AGENT,
        "llmSessionId": llm, "mode": mode,
    }


SESSIONS = [
    session("1", "Drafting the customer update", "2026-10-26T21:40:00Z", pinned=True),
    session("2", "Retiring the legacy billing webhook", "2026-10-26T21:38:00Z", unread=1, llm="20", mode="PLAN"),
    session("3", "Filing the billing incident", "2026-10-26T18:12:00Z", unread=2),
    session("4", "First-reply target for October", "2026-10-25T16:05:00Z"),
    session("5", "Pictures for the winter mailing", "2026-10-24T11:30:00Z"),
    session("6", "First checks for a login fault", "2026-10-23T09:48:00Z"),
]
DRAFT = (
    "Here's the draft:\n\n**Subject: Nightly billing export, the last three runs**\n\n"
    "The nightly billing export failed on the last three runs, so invoices for those nights have not "
    "reached your billing system. The cause is a schema change on our side, and no action is needed from you.\n\n"
    "Two things to settle before you send:\n\n"
    "- **The timezone.** Pin it in the message: the next run is quoted in UTC.\n"
    "- **The backfill.** It stays an open question rather than a promise.\n"
)
MESSAGES = {
    "1": [
        {"role": "user", "content": "Draft an update for customers about the failed billing export."},
        {"role": "thought", "thinking": "Check the runs first, then write it for a customer.", "thinkingMillis": 4200.0},
        {"role": "tool", "actor": "executions", "content": '{"workflow":"Nightly billing export","limit":3}'},
        {"role": "tool", "actor": "memory_save", "content": '{"note":"Backfill still open"}'},
        {"role": "assistant", "content": DRAFT, "at": "2026-10-26T21:40:00Z", "thinking": "Keep it short.", "thinkingMillis": 2100.0},
    ],
    "3": [
        {"role": "user", "content": "File the billing incident and attach the report."},
        {"role": "assistant", "content": "Filed. The report is attached.", "at": "2026-10-26T18:12:00Z",
         "attachments": [{"id": "9", "filename": "incident-report.txt", "contentType": "text/plain", "sizeBytes": 26.0}]},
    ],
    "2": [
        {"role": "user", "content": "Work out how we retire the legacy billing webhook without breaking anything."},
        {"role": "assistant", "content": "I've put the plan to you here. I'll carry it out once you approve it.", "at": "2026-10-26T21:38:00Z"},
    ],
}
PLAN = {
    "id": "7", "kind": "agent.plan", "askedBy": AGENT,
    "question": "The legacy webhook is still called by **Nightly billing export** and **Refund notice**.\n\n"
                "1. Point both at the new billing connection.\n2. Run each once and compare the output.\n"
                "3. Switch the webhook off and leave it a week.",
    "options": [{"id": "approve", "label": "Approve"}, {"id": "keep", "label": "Keep planning"}, {"id": "reject", "label": "Reject"}],
    "items": [], "chosen": None, "multiple": False, "allowsText": True,
}


def answer(query, variables):
    if "workspaces(" in query:
        return {"workspaces": {"content": [WORKSPACE]}}
    if "workspaceAgents" in query:
        return {"workspaceAgents": {"content": AGENTS}}
    if "workspace(" in query:
        return {"workspace": WORKSPACE}
    if "chatSessions" in query:
        return {"chatSessions": SESSIONS}
    if "reasoningEfforts" in query:
        return {"chatSession": {"reasoningEffort": None, "reasoningEfforts": ["low", "medium", "high"]}}
    if "chatSession(" in query:
        return {"chatSession": next(s for s in SESSIONS if s["id"] == variables["id"])}
    if "chatMessages" in query:
        return {"chatMessages": MESSAGES.get(variables["id"], [])}
    if "chatReplySuggestions" in query:
        return {"chatReplySuggestions": ["Make it shorter", "What should I do first?"] if variables["id"] == "1" else []}
    if "sessionRequests" in query:
        return {"sessionRequests": [PLAN] if variables["s"] == "20" else []}
    if "markChatRead" in query:
        return {"markChatRead": {"id": variables["id"]}}
    raise ValueError(f"The demonstration has no answer to: {query[:80]}")


class Handler(BaseHTTPRequestHandler):
    def reply(self, body, kind="application/json", status=200):
        data = body.encode()
        self.send_response(status)
        self.send_header("Content-Type", kind)
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def do_GET(self):
        if self.path == "/api/auth/method":
            self.reply(json.dumps({"method": "NONE", "displayName": "no sign-in at all"}))
        elif self.path == "/api/session":
            self.reply(json.dumps({"username": "alice", "displayName": "alice"}))
        elif self.path == "/api/attachments/9":
            self.reply("A made-up incident report.", "text/plain")
        elif "/follow" in self.path:
            self.reply('event: idle\ndata: {}\n\n', "text/event-stream")
        else:
            self.reply("{}", status=404)

    def do_POST(self):
        body = json.loads(self.rfile.read(int(self.headers.get("Content-Length", 0))) or "{}")
        try:
            self.reply(json.dumps({"data": answer(body["query"], body.get("variables", {}))}))
        except ValueError as refused:
            print(refused, file=sys.stderr)
            self.reply(json.dumps({"errors": [{"message": str(refused)}]}))

    def log_message(self, *_):
        pass


ThreadingHTTPServer(("127.0.0.1", int(sys.argv[1]) if len(sys.argv) > 1 else 18999), Handler).serve_forever()
