const vscode = require("vscode");

function activate() {
  const configured = vscode.workspace
    .getConfiguration("bazelJava")
    .get("binary");
  if (configured) {
    process.env.BAZEL_BINARY = configured;
  }
}

function deactivate() {}

module.exports = { activate, deactivate };
