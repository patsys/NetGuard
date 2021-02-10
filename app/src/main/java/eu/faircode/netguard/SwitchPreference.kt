package eu.faircode.netguardimport

import android.content.Context
import android.preference.SwitchPreference
import android.util.AttributeSet


/*
    This file is part of NetGuard.

    NetGuard is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.

    NetGuard is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU General Public License for more details.

    You should have received a copy of the GNU General Public License
    along with NetGuard.  If not, see <http://www.gnu.org/licenses/>.

    Copyright 2015-2019 by Marcel Bokhorst (M66B)
*/ // https://code.google.com/p/android/issues/detail?id=26194
class SwitchPreference @JvmOverloads constructor(context: Context?, attrs: AttributeSet? = null, defStyle: Int = android.R.attr.switchPreferenceStyle) : SwitchPreference(context, attrs, defStyle)