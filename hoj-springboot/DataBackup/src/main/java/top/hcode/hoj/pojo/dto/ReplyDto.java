package top.hcode.hoj.pojo.dto;

import lombok.Data;
import lombok.experimental.Accessors;
import top.hcode.hoj.pojo.entity.Reply;

/**
 * @Author: Himit_ZH
 * @Date: 2021/6/24 17:00
 * @Description:
 */
@Data
@Accessors(chain = true)
public class ReplyDto {

    private Reply reply;

    private Long did;
}