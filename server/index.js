const { WebSocketServer, WebSocket } = require('ws');

const PORT = process.env.PORT || 3000;
const wss = new WebSocketServer({ port: PORT, host: '0.0.0.0' });

// 存储活动连接
// 配对码 -> { controlledSocket, controllerSocket }
const rooms = new Map();

// socket -> 对应的配对码
const socketToRoomCode = new Map();

// 生成不重复的 6 位数字配对码
function generatePairCode() {
    let code;
    do {
        code = Math.floor(100000 + Math.random() * 900000).toString();
    } while (rooms.has(code));
    return code;
}

wss.on('connection', (ws, req) => {
    const clientIp = req.socket.remoteAddress;
    console.log(`[Server] 新连接来自: ${clientIp}`);

    ws.on('message', (message, isBinary) => {
        // 1. 如果是二进制数据（屏幕截图流），直接中转转发给配对的另一端，不进行 JSON 解析
        if (isBinary) {
            const code = socketToRoomCode.get(ws);
            if (code && rooms.has(code)) {
                const room = rooms.get(code);
                const targetSocket = (ws === room.controlledSocket) ? room.controllerSocket : room.controlledSocket;
                if (targetSocket && targetSocket.readyState === WebSocket.OPEN) {
                    targetSocket.send(message, { binary: true });
                }
            }
            return;
        }

        // 2. 文本消息（控制指令、注册配对请求）处理
        try {
            const data = JSON.parse(message.toString());
            
            // 处理注册受控端 (被控端 - 妈妈)
            if (data.action === 'register_controlled') {
                const code = generatePairCode();
                rooms.set(code, {
                    controlledSocket: ws,
                    controllerSocket: null
                });
                socketToRoomCode.set(ws, code);
                
                ws.send(JSON.stringify({
                    action: 'register_success',
                    code: code
                }));
                console.log(`[Server] 受控端注册成功。配对码: ${code}`);
                return;
            }

            // 处理注册控制端 (主控端 - 子女)
            if (data.action === 'register_controller') {
                const code = data.code;
                if (!rooms.has(code)) {
                    ws.send(JSON.stringify({
                        action: 'pair_failed',
                        reason: '配对码无效或已失效'
                    }));
                    console.log(`[Server] 控制端尝试配对失败，无效配对码: ${code}`);
                    return;
                }

                const room = rooms.get(code);
                if (room.controllerSocket) {
                    ws.send(JSON.stringify({
                        action: 'pair_failed',
                        reason: '该配对码已被其他控制端占用'
                    }));
                    console.log(`[Server] 控制端尝试配对失败，配对码已被占用: ${code}`);
                    return;
                }

                // 绑定控制端
                room.controllerSocket = ws;
                socketToRoomCode.set(ws, code);

                // 通知双方配对成功
                ws.send(JSON.stringify({ action: 'pair_success', role: 'controller', code: code }));
                if (room.controlledSocket && room.controlledSocket.readyState === WebSocket.OPEN) {
                    room.controlledSocket.send(JSON.stringify({ action: 'pair_success', role: 'controlled' }));
                }

                console.log(`[Server] 控制端与受控端配对成功。配对码: ${code}`);
                return;
            }

            // 3. 通用文本指令转发（如点击、滑动指令）
            const code = socketToRoomCode.get(ws);
            if (code && rooms.has(code)) {
                const room = rooms.get(code);
                const targetSocket = (ws === room.controlledSocket) ? room.controllerSocket : room.controlledSocket;
                
                if (targetSocket && targetSocket.readyState === WebSocket.OPEN) {
                    targetSocket.send(message.toString());
                }
            }

        } catch (err) {
            console.error('[Server] 解析文本消息失败:', err.message);
        }
    });

    ws.on('close', () => {
        console.log(`[Server] 连接断开: ${clientIp}`);
        const code = socketToRoomCode.get(ws);
        if (code && rooms.has(code)) {
            const room = rooms.get(code);
            
            // 通知另一端连接已断开
            if (ws === room.controlledSocket) {
                console.log(`[Server] 受控端断开，销毁房间: ${code}`);
                if (room.controllerSocket && room.controllerSocket.readyState === WebSocket.OPEN) {
                    room.controllerSocket.send(JSON.stringify({ action: 'peer_disconnected' }));
                    socketToRoomCode.delete(room.controllerSocket);
                }
                rooms.delete(code);
            } else if (ws === room.controllerSocket) {
                console.log(`[Server] 控制端断开，重置房间: ${code}`);
                room.controllerSocket = null;
                if (room.controlledSocket && room.controlledSocket.readyState === WebSocket.OPEN) {
                    room.controlledSocket.send(JSON.stringify({ action: 'peer_disconnected' }));
                }
            }
        }
        socketToRoomCode.delete(ws);
    });

    ws.on('error', (error) => {
        console.error(`[Server] 连接出错: ${clientIp}`, error);
    });
});

console.log(`[Server] WebSocket 信令中转服务已启动，监听端口: ${PORT}`);
console.log(`[Server] 局域网访问地址: ws://<您的电脑局域网IP>:${PORT}`);
