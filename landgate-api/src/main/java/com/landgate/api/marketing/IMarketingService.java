package com.landgate.api.marketing;

import com.landgate.api.marketing.dto.*;
import java.util.List;

public interface IMarketingService {

    // Redeem codes
    RedeemResult redeem(Long userId, String code);
    RedeemCodeDetail createRedeemCode(RedeemCodeCreateRequest req);
    RedeemCodeDetail getRedeemCode(Long id);
    List<RedeemCodeDetail> listRedeemCodes();
    RedeemCodeDetail updateRedeemCode(Long id, RedeemCodeUpdateRequest req);
    void deleteRedeemCode(Long id);

    // Promo codes
    PromoValidation validatePromo(String code, java.math.BigDecimal orderAmount);
    PromoCodeDetail createPromoCode(PromoCodeCreateRequest req);
    List<PromoCodeDetail> listPromoCodes();
    PromoCodeDetail updatePromoCode(Long id, PromoCodeUpdateRequest req);
    void deletePromoCode(Long id);

    // Announcements
    List<AnnouncementDetail> listActive();
    List<AnnouncementDetail> listAll();
    AnnouncementDetail createAnnouncement(AnnouncementCreateRequest req);
    AnnouncementDetail updateAnnouncement(Long id, AnnouncementUpdateRequest req);
    void deleteAnnouncement(Long id);
    void publish(Long id);
    void unpublish(Long id);
}
