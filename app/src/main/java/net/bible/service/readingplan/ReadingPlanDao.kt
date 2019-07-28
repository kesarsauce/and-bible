/*
 * Copyright (c) 2018 Martin Denham, Tuomas Airaksinen and the And Bible contributors.
 *
 * This file is part of And Bible (http://github.com/AndBible/and-bible).
 *
 * And Bible is free software: you can redistribute it and/or modify it under the
 * terms of the GNU General Public License as published by the Free Software Foundation,
 * either version 3 of the License, or (at your option) any later version.
 *
 * And Bible is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY;
 * without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along with And Bible.
 * If not, see http://www.gnu.org/licenses/.
 *
 */

package net.bible.service.readingplan

import android.util.Log

import net.bible.android.BibleApplication
import net.bible.android.SharedConstants
import net.bible.android.view.activity.readingplan.DailyReading
import net.bible.service.common.AndRuntimeException

import org.apache.commons.lang3.StringUtils
import org.crosswire.common.util.IOUtil
import org.crosswire.jsword.versification.Versification
import org.crosswire.jsword.versification.system.SystemKJV
import org.crosswire.jsword.versification.system.SystemNRSVA
import org.crosswire.jsword.versification.system.Versifications

import java.io.ByteArrayOutputStream
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream
import java.util.ArrayList
import java.util.Properties

/**
 * @author Martin Denham [mjdenham at gmail dot com]
 */
class ReadingPlanDao {
    private var cachedPlanProperties: ReadingPlanProperties? = null

    val readingPlanList: List<ReadingPlanInfoDto>
        get() {
            try {
                val codes = allReadingPlanCodes

                val planInfoList = ArrayList<ReadingPlanInfoDto>()
                for (code in codes) {
                    planInfoList.add(getReadingPlanInfoDto(code))
                }

                return planInfoList

            } catch (e: Exception) {
                Log.e(TAG, "Error getting reading plans", e)
                throw AndRuntimeException("Error getting reading plans", e)
            }

        }

    /** look in assets/readingplan and sdcard/jsword/readingplan for reading plans and return a list of all codes
     */
    private val allReadingPlanCodes: List<String>
        @Throws(IOException::class)
        get() {

            val resources = BibleApplication.application.resources
            val assetManager = resources.assets

            val allCodes = ArrayList<String>()

            val internalPlans = assetManager.list(READING_PLAN_FOLDER)
			if(internalPlans != null) {
				allCodes.addAll(getReadingPlanCodes(internalPlans))
			}

            val userPlans = USER_READING_PLAN_FOLDER.list()
            if(userPlans != null) {
                allCodes.addAll(getReadingPlanCodes(userPlans))
            }

            return allCodes
        }

    /** get a list of all days readings in a plan
     */
    fun getReadingList(planName: String): List<OneDaysReadingsDto> {

        val planInfo = getReadingPlanInfoDto(planName)

        val properties = getPlanProperties(planName)

        val list = ArrayList<OneDaysReadingsDto>()
        for ((key1, value1) in properties) {
            val key = key1 as String
            val value = value1 as String
            if (StringUtils.isNumeric(key)) {
                val day = Integer.parseInt(key)
                val daysReading = OneDaysReadingsDto(day, value, planInfo)
                list.add(daysReading)
            }
        }
        list.sort()

        return list
    }

    /** get readings for one day
     */
    fun getReading(planName: String, dayNo: Int): OneDaysReadingsDto {
        val properties = getPlanProperties(planName)

        val readings = properties[Integer.toString(dayNo)] as String?
        Log.d(TAG, "Readings for day:" + readings)
        return OneDaysReadingsDto(dayNo, readings, getReadingPlanInfoDto(planName))
    }

    /** get last day number - there may be missed days so cannot simply do props.size()
     */
    fun getNumberOfPlanDays(planCode: String): Int {
        if (cachedPlanProperties != null && cachedPlanProperties?.planCode == planCode)
            return cachedPlanProperties?.numberOfPlanDays ?: 0

        return getNumberOfPlanDays(getPlanProperties(planCode))
    }

    private fun getNumberOfPlanDays(properties: ReadingPlanProperties): Int {
        var maxDayNo = 0

        for (oDayNo in properties.keys) {
            val dayNoStr = oDayNo as String
            if (StringUtils.isNumeric(dayNoStr)) {
                val dayNo = Integer.parseInt(dayNoStr)
                maxDayNo = Math.max(maxDayNo, dayNo)
            } else {
                if (!VERSIFICATION.equals(dayNoStr, ignoreCase = true)) {
                    Log.e(TAG, "Invalid day number:$dayNoStr")
                }
            }
        }

        return maxDayNo
    }

    /**
     * Get versification specified in properties file e.g. 'Versification=Vulg'
     * Default to KJV.
     * If specified Versification is not found then use NRSVA because it includes most books possible
     */
    private fun getReadingPlanVersification(planCode: String): Versification {
        if (cachedPlanProperties != null && cachedPlanProperties?.planCode == planCode)
            return cachedPlanProperties?.versification!!

        return getReadingPlanVersification(getPlanProperties(planCode))
    }

    private fun getReadingPlanVersification(properties: ReadingPlanProperties): Versification {
        var versification: Versification
        try {
            val versificationName = properties.getProperty(VERSIFICATION, DEFAULT_VERSIFICATION)
            versification = Versifications.instance().getVersification(versificationName)
        } catch (e: Exception) {
            Log.e(TAG, "Error loading versification from Reading plan:${properties.planCode}")
            versification = Versifications.instance().getVersification(INCLUSIVE_VERSIFICATION)
        }

        return versification
    }

    private fun getReadingPlanInfoDto(planCode: String): ReadingPlanInfoDto {
        Log.d(TAG, "Get reading plan info:$planCode")
        val info = ReadingPlanInfoDto(planCode)

        info.planName = getPlanName(planCode)
        info.planDescription = getPlanDescription(planCode)
        info.numberOfPlanDays = getNumberOfPlanDays(planCode)
        info.versification = getReadingPlanVersification(planCode)

        return info
    }

    private fun getPlanName(planCode: String): String {
        return DailyReading.ABDistributedPlanDetailArray.find { it.planCode == planCode }?.planName
            ?: getPlanProperties(planCode).planName ?: planCode
    }

    private fun getPlanDescription(planCode: String): String {
        return DailyReading.ABDistributedPlanDetailArray.find { it.planCode == planCode } ?.planDescription
            ?: getPlanProperties(planCode).planDescription ?: ""
    }

    private fun getReadingPlanCodes(files: Array<String>): List<String> {
        val codes = ArrayList<String>()
		for (file in files) {
			// this if statement ensures we only deal with .properties files - not folders or anything else
			if (file.endsWith(DOT_PROPERTIES)) {
				// remove the file extension to get the code
				codes.add(file.replace(DOT_PROPERTIES, ""))
			}
		}
        return codes
    }

    /* either load reading plan info from assets/readingplan or sdcard/jsword/readingplan
	 */
    @Synchronized
    private fun getPlanProperties(planCode: String): ReadingPlanProperties {
        if (planCode != cachedPlanProperties?.planCode) {
            val resources = BibleApplication.application.resources
            val assetManager = resources.assets
            val filename = planCode + DOT_PROPERTIES

            // Read from the /assets directory
            val properties = ReadingPlanProperties()
            var inputStreamRaw: InputStream? = null
            try {
                // check to see if a user has created his own reading plan with this name
                val userReadingPlanFile = File(USER_READING_PLAN_FOLDER, filename)
                val isUserPlan = userReadingPlanFile.exists()

                if (!isUserPlan) {
                    inputStreamRaw = assetManager.open(READING_PLAN_FOLDER + File.separator + filename)
                } else {
                    inputStreamRaw = FileInputStream(userReadingPlanFile)
                }

                val byteArrayForReuse = ByteArrayOutputStream().apply {write(inputStreamRaw?.readBytes())}
                properties.load(ByteArrayInputStream(byteArrayForReuse.toByteArray()))
                properties.planCode = planCode
                properties.numberOfPlanDays = getNumberOfPlanDays(properties)
                properties.versification = getReadingPlanVersification(properties)
                getNameAndDescFromProperties(ByteArrayInputStream(byteArrayForReuse.toByteArray()), properties)

                Log.d(TAG, "The properties are now loaded")
                Log.d(TAG, "properties: $properties")

                // cache it so we don't constantly reload the properties
                cachedPlanProperties = properties

            } catch (e: IOException) {
                System.err.println("Failed to open reading plan property file")
                e.printStackTrace()
            } finally {
                IOUtil.close(inputStreamRaw)
            }
        }
        return cachedPlanProperties!!
    }

    private fun getNameAndDescFromProperties(inputStream: InputStream, properties: ReadingPlanProperties) {
        var lineCount = 0
        var loopCount = 0
        // Get first commented lines from file for Plan Name (first line)
        // and Description (following commented lines) up to line 5: otherwise any
        // commented lines further down in the file will also get added to description
        inputStream.bufferedReader().forEachLine {
            if (it.startsWith("#") && loopCount < 5) {
                val lineWithoutCommentMarks: String = it.trim().replaceFirst("^(\\s*#*\\s*)".toRegex(), "")
                Log.d(TAG, lineWithoutCommentMarks)
                if (lineCount == 0) {
                    properties.planName = StringUtils.trim(lineWithoutCommentMarks)
                } else {
                    properties.planDescription = StringUtils.trim(
                        "${properties.planDescription ?: ""} $lineWithoutCommentMarks "
                    )
                }
                lineCount++
            }
            loopCount++
        }
    }

    private class ReadingPlanProperties : Properties() {
        var planCode = ""
        var planName: String? = null
        var planDescription: String? = null
        var versification: Versification? = null
        var numberOfPlanDays = 0
    }

    companion object {

        private val READING_PLAN_FOLDER = SharedConstants.READINGPLAN_DIR_NAME
        private val USER_READING_PLAN_FOLDER = SharedConstants.MANUAL_READINGPLAN_DIR
        private val DOT_PROPERTIES = ".properties"
        private val VERSIFICATION = "Versification"
        private val DEFAULT_VERSIFICATION = SystemKJV.V11N_NAME
        private val INCLUSIVE_VERSIFICATION = SystemNRSVA.V11N_NAME

        private val TAG = "ReadingPlanDao"
    }
}
