package com.abbank.notification.consumer;

import com.abbank.notification.model.CustomerProfile;

import java.util.Optional;

/**
 * Resolves an {@code accountId} to a {@link CustomerProfile} containing
 * the customer's email address and phone number.
 *
 * <p>In production this should call the Customer Profile Service or a
 * read-model backed by the CDC-sourced CUSTOMERS table. The
 * {@link MockCustomerResolver} is provided for local development and testing.
 */
public interface CustomerResolver {

    /**
     * Look up contact details for the given account.
     *
     * @param accountId the numeric account identifier from the notification event
     * @return a populated profile, or {@link Optional#empty()} if not found
     */
    Optional<CustomerProfile> resolve(Long accountId);
}
