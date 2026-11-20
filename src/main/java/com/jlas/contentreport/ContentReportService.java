package com.jlas.contentreport;

import com.jlas.auth.repository.UserRepository;
import com.jlas.common.exception.ApiException;
import com.jlas.common.exception.ErrorCode;
import com.jlas.moderation.service.UserActionPolicyPort;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import static com.jlas.contentreport.ContentReportDtos.*;

@Service @RequiredArgsConstructor
public class ContentReportService {
    private final ContentReportRepository reports;
    private final ContentReportTargets targets;
    private final UserRepository users;
    private final UserActionPolicyPort actions;

    @Transactional
    public Saved create(Long userId, Request request) {
        if(userId==null)throw new ApiException(ErrorCode.UNAUTHORIZED);
        actions.assertCanAct(userId);
        // Serialize one user's submissions so the daily quota also holds for concurrent tabs.
        users.findForCreditUpdate(userId).orElseThrow(()->new ApiException(ErrorCode.UNAUTHORIZED));
        if(request.reason()==null)throw new ApiException(ErrorCode.VALIDATION_FAILED,"Chọn lý do báo cáo.");
        String note=request.note()==null?"":request.note().trim();
        if(note.length()>1000 || request.reason()==Reason.OTHER && note.isEmpty())
            throw new ApiException(ErrorCode.VALIDATION_FAILED,"Vui lòng mô tả lý do khác (tối đa 1000 ký tự).");
        var target=targets.resolve(userId,request);
        if(reports.hasOpen(userId,target.type(),target.code()))throw duplicate();
        if(reports.countToday(userId)>=20)throw new ApiException(ErrorCode.TOO_MANY_REQUESTS,"Bạn đã gửi 20 báo cáo hôm nay. Vui lòng thử lại vào ngày mai.");
        try{return new Saved(reports.insert(userId,request,target,note.isEmpty()?null:note));}
        catch(DuplicateKeyException error){throw duplicate();}
    }
    @Transactional(readOnly=true)
    public ResultPage list(Status status, Target type, int page) {
        if(page<0 || page>100000)throw new ApiException(ErrorCode.VALIDATION_FAILED,"Trang không hợp lệ.");
        return reports.list(status,type,page);
    }
    @Transactional(readOnly=true)
    public Row detail(Long id) {return reports.find(id).orElseThrow(()->new ApiException(ErrorCode.NOT_FOUND));}
    @Transactional
    public Row resolve(Long id,Long userId,Resolution request) {
        if(userId==null)throw new ApiException(ErrorCode.UNAUTHORIZED);
        if(request.status()==null || request.status()==Status.OPEN)throw new ApiException(ErrorCode.VALIDATION_FAILED,"Chọn đã xử lý hoặc từ chối.");
        detail(id);
        if(reports.resolve(id,userId,request)==0)throw new ApiException(ErrorCode.CONFLICT,"Báo cáo này đã được xử lý. Vui lòng tải lại.");
        return detail(id);
    }
    private static ApiException duplicate(){return new ApiException(ErrorCode.CONFLICT,"Bạn đã báo cáo nội dung này. Người biên soạn đang xem xét.");}
}
