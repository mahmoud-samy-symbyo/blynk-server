package cc.blynk.server.application.handlers.sharing.logic;

import cc.blynk.server.application.handlers.sharing.auth.AppShareStateHolder;
import cc.blynk.server.core.dao.SessionDao;
import cc.blynk.server.core.model.DashBoard;
import cc.blynk.server.core.model.auth.Session;
import cc.blynk.server.core.model.enums.PinType;
import cc.blynk.server.core.model.widgets.FrequencyWidget;
import cc.blynk.server.core.model.widgets.Target;
import cc.blynk.server.core.model.widgets.Widget;
import cc.blynk.server.core.protocol.exceptions.IllegalCommandBodyException;
import cc.blynk.server.core.protocol.model.messages.StringMessage;
import cc.blynk.utils.ParseUtil;
import cc.blynk.utils.StringUtils;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import static cc.blynk.server.application.handlers.main.logic.HardwareAppLogic.processDeviceSelectorCommand;
import static cc.blynk.server.core.protocol.enums.Command.APP_SYNC;
import static cc.blynk.server.core.protocol.enums.Command.HARDWARE;
import static cc.blynk.utils.BlynkByteBufUtil.deviceNotInNetwork;
import static cc.blynk.utils.BlynkByteBufUtil.illegalCommandBody;
import static cc.blynk.utils.BlynkByteBufUtil.makeUTF8StringMessage;
import static cc.blynk.utils.BlynkByteBufUtil.noActiveDash;
import static cc.blynk.utils.BlynkByteBufUtil.notAllowed;
import static cc.blynk.utils.StringUtils.split2;
import static cc.blynk.utils.StringUtils.split2Device;
import static cc.blynk.utils.StringUtils.split3;

/**
 * The Blynk Project.
 * Created by Dmitriy Dumanskiy.
 * Created on 2/1/2015.
 *
 */
public class HardwareAppShareLogic {

    private static final Logger log = LogManager.getLogger(HardwareAppShareLogic.class);

    private final SessionDao sessionDao;

    public HardwareAppShareLogic(SessionDao sessionDao) {
        this.sessionDao = sessionDao;
    }

    public void messageReceived(ChannelHandlerContext ctx, AppShareStateHolder state, StringMessage message) {
        Session session = sessionDao.userSession.get(state.userKey);

        String[] split = split2(message.body);

        String[] dashIdAndTargetIdString = split2Device(split[0]);
        int dashId = ParseUtil.parseInt(dashIdAndTargetIdString[0]);
        //deviceId or tagId or device selector widget id
        int targetId = 0;

        //new logic for multi devices
        if (dashIdAndTargetIdString.length == 2) {
            targetId = ParseUtil.parseInt(dashIdAndTargetIdString[1]);
        }

        DashBoard dash = state.user.profile.getDashByIdOrThrow(dashId);

        if (!dash.isActive) {
            log.debug("No active dashboard.");
            ctx.writeAndFlush(noActiveDash(message.id), ctx.voidPromise());
            return;
        }

        if (!dash.isShared) {
            log.debug("Dashboard is not shared. User : {}, {}", state.user.email, ctx.channel().remoteAddress());
            ctx.writeAndFlush(notAllowed(message.id), ctx.voidPromise());
            return;
        }

        //sending message only if widget assigned to device or tag has assigned devices
        Target target = dash.getTarget(targetId);
        if (target == null) {
            log.debug("No assigned target id for received command.");
            return;
        }

        int[] deviceIds = target.getDeviceIds();

        if (deviceIds.length == 0) {
            log.debug("No devices assigned to target.");
            return;
        }

        char operation = split[1].charAt(1);
        switch (operation) {
            case 'u' :
                String[] splitBody = split3(split[1]);
                processDeviceSelectorCommand(ctx, session, dash, message, splitBody);
                break;
            case 'w':
                splitBody = split3(split[1]);

                if (splitBody.length < 3) {
                    log.debug("Not valid write command.");
                    ctx.writeAndFlush(illegalCommandBody(message.id), ctx.voidPromise());
                    return;
                }

                PinType pinType = PinType.getPinType(splitBody[0].charAt(0));
                byte pin = ParseUtil.parseByte(splitBody[1]);
                String value = splitBody[2];
                long now = System.currentTimeMillis();

                for (int deviceId : deviceIds) {
                    dash.update(deviceId, pin, pinType, value, now);
                }

                //additional state for tag widget itself
                if (target.isTag()) {
                    dash.update(targetId, pin, pinType, value, now);
                }

                final String sharedToken = state.token;
                if (sharedToken != null) {
                    for (Channel appChannel : session.appChannels) {
                        if (appChannel != ctx.channel() && appChannel.isWritable()
                                && Session.needSync(appChannel, sharedToken)) {
                            appChannel.writeAndFlush(
                                    makeUTF8StringMessage(APP_SYNC, message.id, message.body),
                                    appChannel.voidPromise());
                        }
                    }
                }

                if (session.sendMessageToHardware(dashId, HARDWARE, message.id, split[1], deviceIds)) {
                    log.debug("No device in session.");
                    ctx.writeAndFlush(deviceNotInNetwork(message.id), ctx.voidPromise());
                }
                break;


            //todo fully remove this section???
            case 'r':
                Widget widget = dash.findWidgetByPin(targetId, split[1].split(StringUtils.BODY_SEPARATOR_STRING));
                if (widget == null) {
                    throw new IllegalCommandBodyException("No frequency widget for read command.");
                }

                if (!(widget instanceof FrequencyWidget)) {
                    //corner case for 3-d parties. sometimes users need to read pin state
                    // even from non-frequency widgets
                    if (session.sendMessageToHardware(dashId, HARDWARE, message.id, split[1], targetId)) {
                        log.debug("No device in session.");
                        ctx.writeAndFlush(deviceNotInNetwork(message.id), ctx.voidPromise());
                    }
                }
                break;
        }
    }
}
