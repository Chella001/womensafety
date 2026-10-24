package com.womensafety.validation;

import com.google.i18n.phonenumbers.NumberParseException;
import com.google.i18n.phonenumbers.PhoneNumberUtil;
import com.google.i18n.phonenumbers.Phonenumber;
import com.womensafety.exception.InvalidRequestException;
import org.springframework.stereotype.Component;

/** Phone-number validation and E.164 normalisation, backed by Google's libphonenumber. */
@Component
public class PhoneNumbers {

    private final PhoneNumberUtil util = PhoneNumberUtil.getInstance();

    /**
     * Parses {@code raw} and returns it in E.164 form (for example {@code +919876543210}).
     *
     * @param defaultRegion region (ISO 3166-1 alpha-2) used when {@code raw} has no leading '+'; may be null
     * @throws InvalidRequestException when the number is not a valid number
     */
    public String toE164(String raw, String defaultRegion, String field) {
        try {
            Phonenumber.PhoneNumber number = util.parse(raw.strip(), defaultRegion);
            if (!util.isValidNumber(number)) {
                throw new InvalidRequestException(field, "Enter a valid phone number including the country code.");
            }
            return util.format(number, PhoneNumberUtil.PhoneNumberFormat.E164);
        } catch (NumberParseException e) {
            throw new InvalidRequestException(field, "Enter a valid phone number including the country code.");
        }
    }

    /** True when {@code value} is already a valid number in strict E.164 form (leading '+', digits only). */
    public boolean isValidE164(String value) {
        if (value == null || !value.matches("^\\+[1-9][0-9]{6,14}$")) {
            return false;
        }
        try {
            return util.isValidNumber(util.parse(value, null));
        } catch (NumberParseException e) {
            return false;
        }
    }

    /** The region of an E.164 number, or null when unknown. Used as the default for the user's contacts. */
    public String regionOf(String e164) {
        try {
            return util.getRegionCodeForNumber(util.parse(e164, null));
        } catch (NumberParseException e) {
            return null;
        }
    }

    /** "+919876543210" becomes "+91 ••••• ••210" style masking: only the country code and last 3 digits remain. */
    public static String mask(String e164) {
        if (e164 == null || e164.length() < 8) {
            return "••••";
        }
        return e164.substring(0, 3) + "•".repeat(e164.length() - 6) + e164.substring(e164.length() - 3);
    }
}
