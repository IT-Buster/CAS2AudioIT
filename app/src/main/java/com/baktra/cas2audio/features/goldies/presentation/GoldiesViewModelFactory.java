package com.baktra.cas2audio.features.goldies.presentation;

import androidx.annotation.NonNull;
import androidx.lifecycle.ViewModel;
import androidx.lifecycle.ViewModelProvider;

import com.baktra.cas2audio.features.goldies.domain.GoldiesRepository;
import com.baktra.cas2audio.features.settings.repository.SettingsRepository;

public class GoldiesViewModelFactory implements ViewModelProvider.Factory {

        private final GoldiesRepository goldiesRepo;
        private final SettingsRepository settingsRepo;
        private final String playlistSystemCode;

        public GoldiesViewModelFactory(GoldiesRepository goldiesRepo, SettingsRepository settingsRepo, String playlistSystemCode) {
            this.playlistSystemCode = playlistSystemCode;
            this.goldiesRepo=goldiesRepo;
            this.settingsRepo=settingsRepo;
        }

        public <T extends ViewModel> T create(@NonNull Class<T> modelClass) {

            if (modelClass.isAssignableFrom(GoldiesViewModel.class)) {
                return (T) new GoldiesViewModel(goldiesRepo, settingsRepo, playlistSystemCode);
            }
            throw new IllegalArgumentException("Unknown ViewModel class");
        }

}
