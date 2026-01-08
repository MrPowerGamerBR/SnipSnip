var clients = workspace.stackingOrder;
var result = {}
var windows = []
for (var i = 0; i < clients.length; i++) {
    var client = clients[i];
    windows.push({
        "caption": client.caption,
        "internalId": client.internalId,
        "geometry": {
            "x": client.frameGeometry.x,
            "y": client.frameGeometry.y,
            "width": client.frameGeometry.width,
            "height": client.frameGeometry.height
        },
        "minimized": client.minimized,
        "pid": client.pid,
        "resourceClass": client.resourceClass,
        "resourceName": client.resourceName
    })
}
result.cursor = {
    "x": workspace.cursorPos.x,
    "y": workspace.cursorPos.y
}
result.windows = windows
// The "SNIPSNIP_OUTPUT" is a magic value because we somehow need to figure out the output
console.log("SNIPSNIP_OUTPUT_{randomUUID}:" + JSON.stringify(result))