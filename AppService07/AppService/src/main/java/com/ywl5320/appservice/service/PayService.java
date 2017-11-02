package com.ywl5320.appservice.service;

import com.alipay.api.AlipayApiException;
import com.alipay.api.AlipayClient;
import com.alipay.api.DefaultAlipayClient;
import com.alipay.api.domain.AlipayTradeAppPayModel;
import com.alipay.api.request.AlipayTradeAppPayRequest;
import com.alipay.api.response.AlipayTradeAppPayResponse;
import com.ywl5320.appservice.bean.*;
import com.ywl5320.appservice.dao.OrderDao;
import com.ywl5320.appservice.dao.UserDao;
import com.ywl5320.appservice.util.CommonUtils;
import com.ywl5320.appservice.util.MD5;
import com.ywl5320.appservice.util.RestFulUtil;
import com.ywl5320.appservice.util.Util;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

/**
 * Created by ywl5320 on 2017/10/19.
 */
@Transactional
public class PayService {

    /**
     * 支付宝
     */
    public static final String ALI_APPID = "";

    public static final String ALI_RSA2_PRIVATE_KEY = "";

    public static final String ALI_PUBLIC_KEY = "";

    /**
     * 微信
     */
    public static final String WX_APPID = "";

    public static final String WX_KEY = "";

    public static final String WX_MCHID = "";

    String lockSuo = "createOrder";

    @Autowired
    private OrderDao orderDao;

    public RestFulBean<BaseBean> createOrder(int userId, String phone, String payGoods, String payMoney, int payWay, String subject)
    {

        if(payWay != 1 && payWay != 2)
        {
            return RestFulUtil.getInstance().getResuFulBean(null, 1, "支付方式不对");
        }
        String payTime = String.valueOf(System.currentTimeMillis());
        String orderNo = "";
        OrderBean order = null;
        AliPayBean aliPayBean = null;
        WxPayBean wxPayBean = null;
        synchronized (lockSuo) {
            try {
                orderNo = MD5.encryptMD5(userId + payTime + payMoney + subject + "md5_create_order");//生成订单ID
                OrderBean orderBean = new OrderBean();
                orderBean.setPhone(phone);
                orderBean.setSubject(subject);
                orderBean.setOrderNo(orderNo);
                orderBean.setUserId(userId);
                orderBean.setPayGoods(payGoods);
                orderBean.setPayMoney(payMoney);
                orderBean.setPayStatus(0);//0：未支付 1：已支付
                orderBean.setPayTime(CommonUtils.getNowTime());
                orderBean.setPayWay(payWay);//1：支付宝支付 2：微信支付

                orderDao.createOrder(orderBean);

                order = orderDao.getOrderById(userId, orderNo);

                if(order != null)
                {
                    if(payWay == 1)//支付宝
                    {
                        //实例化客户端
                        AlipayClient alipayClient = new DefaultAlipayClient("https://openapi.alipay.com/gateway.do", ALI_APPID, ALI_RSA2_PRIVATE_KEY, "json", "utf-8", ALI_PUBLIC_KEY, "RSA2");
                        //实例化具体API对应的request类,类名称和接口名称对应,当前调用接口名称：alipay.trade.app.pay
                        AlipayTradeAppPayRequest request = new AlipayTradeAppPayRequest();
                        //SDK已经封装掉了公共参数，这里只需要传入业务参数。以下方法为sdk的model入参方式(model和biz_content同时存在的情况下取biz_content)。
                        AlipayTradeAppPayModel model = new AlipayTradeAppPayModel();
                        model.setBody(orderBean.getPayGoods());
                        model.setSubject(orderBean.getSubject());
                        model.setOutTradeNo(orderBean.getOrderNo());
                        model.setTimeoutExpress("30m");
                        model.setTotalAmount(orderBean.getPayMoney());
                        model.setProductCode("QUICK_MSECURITY_PAY");
                        request.setBizModel(model);
                        request.setNotifyUrl("http://10.50.50.205:8080/pay/verifyalipayresult.do");//注意：实际情况填写自己的服务器接口地址，外网才能访问。
                        try {
                            //这里和普通的接口调用不同，使用的是sdkExecute
                            AlipayTradeAppPayResponse response = alipayClient.sdkExecute(request);
                            aliPayBean = new AliPayBean();
                            aliPayBean.setOrderId(response.getOutTradeNo());
                            aliPayBean.setOrderInfo(response.getBody());
//                            System.out.println(response.getBody());//就是orderString 可以直接给客户端请求，无需再做处理。
                        } catch (AlipayApiException e) {
                            e.printStackTrace();
                        }

                    }
                    else if(payWay == 2)//微信支付
                    {
                        SortedMap<String, String> parameters = new TreeMap<String, String>();
                        parameters.put("appid", WX_APPID);
                        parameters.put("body", orderBean.getSubject());
                        parameters.put("mch_id", WX_MCHID);
                        parameters.put("nonce_str", CommonUtils.genNonceStr());
                        parameters.put("notify_url", "http://10.50.50.205:8080/pay/verifywxpayresult.do");//注意：实际情况填写自己的服务器接口地址，外网才能访问。
                        parameters.put("out_trade_no", orderBean.getOrderNo());
                        parameters.put("total_fee", "50");
                        parameters.put("trade_type", "APP");
                        parameters.put("spbill_create_ip", "196.168.1.1");
                        parameters.put("sign", CommonUtils.createSign("UTF-8", parameters, WX_KEY));//传入签名好的参数值

                        StringBuilder xmlBuilder = new StringBuilder();
                        xmlBuilder.append("<xml>");
                        Set es = parameters.entrySet();//所有参与传参的参数按照accsii排序（升序）
                        Iterator it = es.iterator();
                        CommonUtils.createXml(it, xmlBuilder);
                        xmlBuilder.append("</xml>");
                        System.out.println(xmlBuilder.toString());
                        try {
                            String url = "https://api.mch.weixin.qq.com/pay/unifiedorder";
                            byte[] buf = Util.httpPost(url, new String(xmlBuilder.toString().getBytes("UTF-8"), "ISO8859-1"));
                            String content = new String(buf);
                            System.out.println("content:" + content);

                            Map<String, String> map = CommonUtils.xmlToMap(content);
                            String nonceStr = CommonUtils.genNonceStr();
                            String timeStamp = String.valueOf(CommonUtils.genTimeStamp());

                            SortedMap<String, String> signparameters = new TreeMap<String, String>();
                            signparameters.put("appid", map.get("appid"));
                            signparameters.put("partnerid", map.get("mch_id"));
                            signparameters.put("prepayid", map.get("prepay_id"));
                            signparameters.put("package", "Sign=WXPay");
                            signparameters.put("noncestr", nonceStr);
                            signparameters.put("timestamp", timeStamp);

                            wxPayBean = new WxPayBean();
                            wxPayBean.setOrderId(order.getOrderNo());
                            wxPayBean.setAppid(map.get("appid"));
                            wxPayBean.setPartnerid(map.get("mch_id"));
                            wxPayBean.setPrepayid(map.get("prepay_id"));
                            wxPayBean.setNoncestr(nonceStr);
                            wxPayBean.setTimestamp(timeStamp);
                            wxPayBean.setPackageValue("Sign=WXPay");
                            wxPayBean.setSign(CommonUtils.createSign("UTF-8", signparameters, WX_KEY));

                        } catch (Exception e) {
                            e.printStackTrace();
                        }
                    }
                }

            } catch (Exception e) {
                // TODO Auto-generated catch block
                e.printStackTrace();
            }
        }

        if(payWay == 1)
        {
            if(aliPayBean != null)
            {
                return RestFulUtil.getInstance().getResuFulBean(aliPayBean, 0, "支付宝订单生成成功");
            }
            else
            {
                return RestFulUtil.getInstance().getResuFulBean(null, 1, "支付宝订单生成失败");
            }
        }
        else if(payWay == 2)
        {
            if(wxPayBean != null)
            {
                return RestFulUtil.getInstance().getResuFulBean(wxPayBean, 0, "微信订单生成成功");
            }
            else
            {
                return RestFulUtil.getInstance().getResuFulBean(null, 1, "微信订单生成失败");
            }
        }
        else
        {
            return RestFulUtil.getInstance().getResuFulBean(null, 1, "支付方式不对");
        }
    }

    /**
     * 支付宝验证，更新订单信息
     * @param params
     * @return
     */
    public String verifyAliPay(Map<String, String> params)
    {
        OrderBean orderBean = orderDao.getOrderByOrNo(params.get("out_trade_no"));
        if(orderBean != null)
        {
            orderBean.setPayStatus(1);//已经支付
            orderDao.updateOrder(orderBean);
            return "success";
        }
        return "fail";
    }

    /**
     * 微信支付回调更新订单信息
     * @param params
     * @return
     */
    public String verifyWxPay(Map<String, String> params)
    {
        OrderBean orderEntity = orderDao.getOrderByOrNo(params.get("out_trade_no"));
        if(orderEntity != null)
        {
            orderEntity.setPayStatus(1);//已经支付
            orderDao.updateOrder(orderEntity);
            return "success";
        }
        return "fail";
    }

}
