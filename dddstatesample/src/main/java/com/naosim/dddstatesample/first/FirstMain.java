package com.naosim.dddstatesample.first;

import com.naosim.dddstatesample.common.vo.*;
import io.vavr.control.Option;
import io.vavr.control.Validation;
import lombok.AllArgsConstructor;

public class FirstMain {
    public static void main(String[] args) {
        System.out.println("hello world");
    }

    @AllArgsConstructor
    static class UserEntity {
        UserId userId;
        State state;
        OrderDate orderDate;
        Option<ContractStartDate> contractStartDateOption;
        Option<ContractEndDate> contractEndDateOption;
        Option<OrderCancelDate> orderCancelDateOption;

        Validation<Error, UserEntity> onFinishedSetup() { return null; }
        Validation<Error, UserEntity> onOrderCancel() { return null; }
        Validation<Error, UserEntity> onContractEnd() { return null; }
    }
}
